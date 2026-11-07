package com.replaymod.core.files;

import com.replaymod.replaystudio.data.Marker;
import com.replaymod.replaystudio.data.ModInfo;
import com.replaymod.replaystudio.data.ReplayAssetEntry;
import com.replaymod.replaystudio.io.ReplayInputStream;
import com.replaymod.replaystudio.io.ReplayOutputStream;
import com.replaymod.replaystudio.lib.guava.base.Optional;
import com.replaymod.replaystudio.pathing.PathingRegistry;
import com.replaymod.replaystudio.pathing.path.Timeline;
import com.replaymod.replaystudio.protocol.PacketTypeRegistry;
import com.replaymod.replaystudio.replay.AbstractReplayFile;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.replaystudio.replay.ReplayMetaData;
import com.replaymod.replaystudio.studio.ReplayStudio;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

import static com.replaymod.core.files.ReplayFoldersService.*;

/** A fixed outer ReplayFile ABI with a mutable, generation-aware inner delegate. */
public class ManagedReplayFile extends DelegatingReplayFile {
    /**
     * Compatibility constructor for third-party delegates without storage context. Reads and writes remain
     * available, but implicit save/close cannot safely infer an output/tmp ownership boundary and fail closed.
     * The application's handles are always constructed by ReplayFilesService, never by this constructor.
     */
    public ManagedReplayFile(ReplayFile delegate, Runnable onClose) {
        super(unscoped(delegate, onClose));
    }

    private ManagedReplayFile(Mutable delegate) { super(delegate); }

    static ManagedReplayFile create(ReplayFilesService service, ReplayFilesService.Session session,
                                    ReplayFile delegate) throws IOException {
        return new ManagedReplayFile(new Mutable(service, session, delegate, null));
    }

    private static Mutable unscoped(ReplayFile delegate, Runnable onClose) {
        try { return new Mutable(null, null, Objects.requireNonNull(delegate), Objects.requireNonNull(onClose)); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /**
     * AbstractReplayFile's verified implementations use get/write/remove virtually. Reusing them here, rather
     * than forwarding high-level writes directly to ZipReplayFile, tracks ALL high-level mutations and streams.
     */
    private static final class Mutable extends AbstractReplayFile {
        private enum State { OPEN_DIRTY, COMPLETE_SNAPSHOT, PUBLISHED, NEEDS_RECOVERY, CLOSED }
        private final ReplayFilesService service;
        private final ReplayFilesService.Session session;
        private Runnable onClose;
        private ReplayFile delegate;
        private final Set<TrackedOutput> outputs = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<TrackedInput> inputs = Collections.newSetFromMap(new IdentityHashMap<>());
        private State state = State.OPEN_DIRTY;
        private long revision, snapshotRevision = -1;
        private long generation;
        private Path completeSnapshot;
        private ReplayFilesService.Stamp snapshotStamp;
        private boolean changed, journalDirty, exported, publishedPending, closingDelegate;
        private IOException streamFailure;

        Mutable(ReplayFilesService service, ReplayFilesService.Session session, ReplayFile delegate,
                Runnable onClose) throws IOException {
            super(new ReplayStudio());
            this.service = service; this.session = session; this.delegate = delegate; this.onClose = onClose;
            // Arbitrary external delegates may already contain changed entries; never infer that they are clean.
            this.changed = session == null;
        }

        private void scoped() throws IOException {
            if (session == null) throw new IOException("ManagedReplayFile has no logical path/owned workspace; open through ReplayFilesService");
        }
        private void readable() throws IOException {
            if (state == State.CLOSED) throw new IOException("Replay is closed");
            if (delegate == null || closingDelegate || publishedPending) {
                throw new IOException("Replay needs save/rebase recovery before further access" + location());
            }
        }
        private String location() { return session == null ? "" : ": " + session.workspace; }
        private void mutation() throws IOException {
            readable();
            if (streamFailure != null) throw new IOException("Previous stream failure; work retained" + location(), streamFailure);
            if (!journalDirty && session != null) { service.dirty(session); journalDirty = true; }
            revision++;
            changed = true;
            exported = false;
            state = State.OPEN_DIRTY;
        }
        private void validateEntry(String entry) throws IOException {
            ReplayFilesService.entryName(entry);
            if (entry.endsWith("/") || entry.contains("//")) throw new IOException("Not a replay file entry: " + entry);
            if (session != null) {
                checkTree(Path.of(session.generation + ".tmp").resolve("changed").resolve(entry));
                checkPath(Path.of(session.generation + ".tmp").resolve("removed").resolve(entry));
            }
        }
        private void validateCache(String entry) throws IOException {
            ReplayFilesService.entryName(entry);
            if (entry.endsWith("/") || entry.contains("//")) throw new IOException("Not a cache file entry: " + entry);
            if (session != null) checkPath(service.cachePath(session).resolve(entry));
        }
        private Optional<InputStream> track(Optional<InputStream> stream) {
            return stream.isPresent() ? Optional.of(track(stream.get())) : Optional.absent();
        }
        private InputStream track(InputStream in) {
            TrackedInput tracked = new TrackedInput(in, generation); inputs.add(tracked); return tracked;
        }
        private OutputStream track(OutputStream out, boolean cache) {
            TrackedOutput tracked = new TrackedOutput(out, generation, cache); outputs.add(tracked); return tracked;
        }

        @Override public synchronized Optional<InputStream> get(String entry) throws IOException {
            readable(); validateEntry(entry); return track(delegate.get(entry));
        }
        @Override public synchronized Optional<InputStream> getCache(String entry) throws IOException {
            readable(); validateCache(entry); return track(delegate.getCache(entry));
        }
        @Override public synchronized Map<String, InputStream> getAll(Pattern pattern) throws IOException {
            readable();
            Map<String, InputStream> raw = delegate.getAll(pattern);
            Map<String, InputStream> result = new HashMap<>();
            try {
                for (Map.Entry<String, InputStream> entry : raw.entrySet()) {
                    validateEntry(entry.getKey()); result.put(entry.getKey(), track(entry.getValue()));
                }
                return result;
            } catch (IOException failure) {
                for (InputStream in : raw.values()) try { in.close(); } catch (IOException close) { failure.addSuppressed(close); }
                throw failure;
            }
        }
        @Override public synchronized OutputStream write(String entry) throws IOException {
            validateEntry(entry); mutation();
            if (session != null && "recording.tmcpr".equals(entry)) checkTree(session.workspace);
            // The library silently closes a previous writer for the same entry. We refuse that data-loss trap.
            for (TrackedOutput out : outputs) if (!out.cache && entry.equals(out.entry)) throw new IOException("Entry still has an open writer: " + entry);
            TrackedOutput out = (TrackedOutput) track(delegate.write(entry), false); out.entry = entry; return out;
        }
        @Override public synchronized OutputStream writeCache(String entry) throws IOException {
            readable(); validateCache(entry);
            TrackedOutput out = (TrackedOutput) track(delegate.writeCache(entry), true); out.entry = entry; return out;
        }
        @Override public synchronized void remove(String entry) throws IOException {
            validateEntry(entry); mutation();
            for (TrackedOutput out : new ArrayList<>(outputs)) if (!out.cache && entry.equals(out.entry)) out.close();
            delegate.remove(entry);
        }
        @Override public synchronized void removeCache(String entry) throws IOException {
            readable(); validateCache(entry);
            for (TrackedOutput out : new ArrayList<>(outputs)) if (out.cache && entry.equals(out.entry)) out.close();
            delegate.removeCache(entry);
        }

        private void closeOutputs() throws IOException {
            IOException failure = streamFailure;
            for (TrackedOutput out : new ArrayList<>(outputs)) {
                try { out.close(); }
                catch (IOException e) { if (failure == null) failure = e; else if (failure != e) failure.addSuppressed(e); }
            }
            if (failure != null) {
                state = State.NEEDS_RECOVERY;
                throw new IOException("Writer did not close cleanly; delegate, work tree and locks retained" + location(), failure);
            }
        }
        private void closeInputs() throws IOException {
            IOException failure = null;
            for (TrackedInput in : new ArrayList<>(inputs)) {
                try { in.close(); }
                catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
            if (failure != null) throw failure;
        }
        private void makeSnapshot() throws IOException {
            scoped(); closeOutputs();
            Path candidate = service.snapshot(session, delegate);
            completeSnapshot = candidate;
            snapshotStamp = ReplayFilesService.Stamp.read(candidate);
            snapshotRevision = revision;
            journalDirty = false;
            state = State.COMPLETE_SNAPSHOT;
        }
        private boolean snapshotCoversRevision() throws IOException {
            if (completeSnapshot == null || snapshotRevision != revision) return false;
            snapshotStamp.verify(completeSnapshot);
            return true;
        }
        private void disposeDelegate() throws IOException {
            if (session.constructionUncertain) throw new IOException("Library construction left uncertain handles; workspace/locks retained until restart" + location());
            if (delegate == null) return;
            closeInputs();
            service.beforeDelegateClose(session);
            closingDelegate = true;
            try {
                delegate.close();
                delegate = null;
                generation++;
                closingDelegate = false;
            } catch (IOException | RuntimeException e) {
                state = State.NEEDS_RECOVERY;
                throw new IOException("Snapshot preserved but delegate close incomplete; locks retained" + location(), e);
            }
        }
        private void rebasePublished() throws IOException {
            // A previous publish succeeded: never publish again, even if opening the new baseline failed.
            try {
                session.expected = ReplayFilesService.Stamp.read(session.logicalOutput);
                if (!session.expected.hash().equals(snapshotStamp.hash())) throw new IOException("Published replay changed before rebase");
                delegate = service.newDelegate(session, session.logicalOutput);
                changed = false;
                journalDirty = false;
                publishedPending = false;
                state = State.PUBLISHED;
                service.receipt(session, "PUBLISHED");
            } catch (IOException | RuntimeException e) {
                state = State.PUBLISHED;
                throw new IOException("PUBLISHED " + session.logicalOutput + "; rebase pending, retry save() (do not republish)" + location(), e);
            }
        }

        @Override public synchronized void save() throws IOException {
            scoped();
            if (state == State.CLOSED) throw new IOException("Replay is closed");
            if (publishedPending) { rebasePublished(); return; }
            if (!changed && session.expected != null && delegate != null) {
                session.expected.verify(session.logicalOutput);
                return;
            }
            try {
                if (!snapshotCoversRevision()) makeSnapshot();
                closeOutputs();
                disposeDelegate(); // releases original ZIP before Windows SAVE_EXISTING; complete snapshot exists first
                service.publishSnapshot(session, completeSnapshot);
                publishedPending = true;
                exported = false;
                state = State.PUBLISHED;
                rebasePublished();
            } catch (IOException | RuntimeException e) {
                if (!publishedPending && delegate == null && completeSnapshot != null) {
                    try {
                        delegate = service.newDelegate(session, completeSnapshot);
                        journalDirty = false;
                        state = State.COMPLETE_SNAPSHOT;
                        service.receipt(session, "COMPLETE_SNAPSHOT");
                    } catch (IOException | RuntimeException restore) { e.addSuppressed(restore); state = State.NEEDS_RECOVERY; }
                }
                if (e instanceof IOException io) throw io;
                throw new IOException("Saving replay failed; work/snapshot preserved" + location(), e);
            }
        }

        @Override public synchronized void saveTo(File target) throws IOException {
            scoped();
            Path path = service.absolute(target.toPath());
            if (identity(path).equals(identity(session.logicalOutput))) { save(); return; }
            readable();
            if (session.logicalInput != null && identity(path).equals(identity(session.logicalInput))) {
                throw new IOException("Export may not overwrite its opened input: " + path);
            }
            if (!snapshotCoversRevision()) makeSnapshot();
            else closeOutputs();
            service.export(session, completeSnapshot, path);
            exported = true;
            service.receipt(session, "EXPORTED");
            // No rebase here: this remains the same logical replay and revision.
        }

        @Override public synchronized void close() throws IOException {
            if (state == State.CLOSED) return;
            scoped();
            try {
                closeOutputs();
                boolean recovery = changed && !exported && !publishedPending;
                if (changed && !snapshotCoversRevision()) makeSnapshot();
                // Failure above intentionally does NOT call destructive ZipReplayFile.close().
                disposeDelegate();
                state = State.CLOSED;
                service.finish(session, recovery);
                if (onClose != null) { Runnable callback = onClose; onClose = null; callback.run(); }
            } catch (IOException | RuntimeException e) {
                if (state != State.CLOSED) state = State.NEEDS_RECOVERY;
                if (e instanceof IOException io) throw io;
                throw new IOException("Safe close incomplete; work and locks retained" + location(), e);
            }
        }

        private final class TrackedOutput extends OutputStream {
            final OutputStream out;
            final long born;
            final boolean cache;
            String entry;
            boolean closed;
            TrackedOutput(OutputStream out, long born, boolean cache) { this.out = out; this.born = born; this.cache = cache; }
            private void valid() throws IOException {
                readable();
                if (closed || generation != born) throw new IOException("Writer belongs to a closed generation");
            }
            private void failed(IOException e) {
                // Cache errors remain visible, but do not invalidate an otherwise preserved replay revision.
                // A cache writer whose close fails stays in outputs and must still close before release.
                if (!cache && streamFailure == null) streamFailure = e;
            }
            @Override public void write(int b) throws IOException {
                synchronized (Mutable.this) {
                    valid(); if (!cache) mutation();
                    try { out.write(b); } catch (IOException e) { failed(e); throw e; }
                }
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                synchronized (Mutable.this) {
                    valid(); if (!cache) mutation();
                    try { out.write(b, off, len); } catch (IOException e) { failed(e); throw e; }
                }
            }
            @Override public void flush() throws IOException {
                synchronized (Mutable.this) { valid(); try { out.flush(); } catch (IOException e) { failed(e); throw e; } }
            }
            @Override public void close() throws IOException {
                synchronized (Mutable.this) {
                    if (closed) return;
                    try {
                        if (session != null && "recording.tmcpr".equals(entry)) {
                            // ZipReplayFile's recording wrapper writes CRC metadata and recursively invalidates cache.
                            checkTree(session.workspace);
                            checkTree(service.cachePath(session));
                        }
                        out.close(); closed = true; outputs.remove(this);
                    } catch (IOException e) { failed(e); throw e; }
                }
            }
        }
        private final class TrackedInput extends InputStream {
            final InputStream in; final long born; boolean closed;
            TrackedInput(InputStream in, long born) { this.in = in; this.born = born; }
            private void valid() throws IOException {
                readable(); if (closed || generation != born) throw new IOException("Reader belongs to a closed generation");
            }
            @Override public int read() throws IOException { synchronized (Mutable.this) { valid(); return in.read(); } }
            @Override public int read(byte[] b, int off, int len) throws IOException { synchronized (Mutable.this) { valid(); return in.read(b, off, len); } }
            @Override public long skip(long n) throws IOException { synchronized (Mutable.this) { valid(); return in.skip(n); } }
            @Override public int available() throws IOException { synchronized (Mutable.this) { valid(); return in.available(); } }
            @Override public void close() throws IOException {
                synchronized (Mutable.this) { if (!closed) { in.close(); closed = true; inputs.remove(this); } }
            }
        }

        // Hold one monitor across the entire high-level operation; save/rebase cannot bisect it.
        @Override public synchronized ReplayMetaData getMetaData() throws IOException { readable(); return super.getMetaData(); }
        @Override public synchronized void writeMetaData(PacketTypeRegistry registry, ReplayMetaData data) throws IOException { readable(); super.writeMetaData(registry, data); }
        @Override public synchronized ReplayInputStream getPacketData(PacketTypeRegistry registry) throws IOException { readable(); return super.getPacketData(registry); }
        @Override public synchronized ReplayOutputStream writePacketData() throws IOException { readable(); return super.writePacketData(); }
        @Override public synchronized Map<Integer, String> getResourcePackIndex() throws IOException { readable(); return super.getResourcePackIndex(); }
        @Override public synchronized void writeResourcePackIndex(Map<Integer, String> index) throws IOException { readable(); super.writeResourcePackIndex(index); }
        @Override public synchronized Optional<InputStream> getResourcePack(String hash) throws IOException { readable(); return super.getResourcePack(hash); }
        @Override public synchronized OutputStream writeResourcePack(String hash) throws IOException { readable(); return super.writeResourcePack(hash); }
        @Override public synchronized Map<String, Timeline> getTimelines(PathingRegistry registry) throws IOException { readable(); return super.getTimelines(registry); }
        @Override public synchronized void writeTimelines(PathingRegistry registry, Map<String, Timeline> timelines) throws IOException { readable(); super.writeTimelines(registry, timelines); }
        @Override public synchronized Optional<BufferedImage> getThumb() throws IOException { readable(); return super.getThumb(); }
        @Override public synchronized void writeThumb(BufferedImage image) throws IOException { readable(); super.writeThumb(image); }
        @Override public synchronized Optional<InputStream> getThumbBytes() throws IOException { readable(); return super.getThumbBytes(); }
        @Override public synchronized void writeThumbBytes(byte[] bytes) throws IOException { readable(); super.writeThumbBytes(bytes); }
        @Override public synchronized Optional<Set<UUID>> getInvisiblePlayers() throws IOException { readable(); return super.getInvisiblePlayers(); }
        @Override public synchronized void writeInvisiblePlayers(Set<UUID> players) throws IOException { readable(); super.writeInvisiblePlayers(players); }
        @Override public synchronized Optional<Set<Marker>> getMarkers() throws IOException { readable(); return super.getMarkers(); }
        @Override public synchronized void writeMarkers(Set<Marker> markers) throws IOException { readable(); super.writeMarkers(markers); }
        @Override public synchronized Collection<ReplayAssetEntry> getAssets() throws IOException { readable(); return super.getAssets(); }
        @Override public synchronized Optional<InputStream> getAsset(UUID id) throws IOException { readable(); return super.getAsset(id); }
        @Override public synchronized OutputStream writeAsset(ReplayAssetEntry asset) throws IOException { readable(); return super.writeAsset(asset); }
        @Override public synchronized void removeAsset(UUID id) throws IOException { readable(); super.removeAsset(id); }
        @Override public synchronized Collection<ModInfo> getModInfo() throws IOException { readable(); return super.getModInfo(); }
        @Override public synchronized void writeModInfo(Collection<ModInfo> info) throws IOException { readable(); super.writeModInfo(info); }
    }
}
