package com.replaymod.core.files;

import com.replaymod.core.ReplayMod;
import com.replaymod.core.gui.RestoreReplayGui;
import com.replaymod.replaystudio.replay.ReplayFile;
import com.replaymod.replaystudio.replay.ZipReplayFile;
import com.replaymod.replaystudio.studio.ReplayStudio;
import de.johni0702.minecraft.gui.container.GuiScreen;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.replaymod.core.files.ReplayFoldersService.*;

/** Storage transactions. Locks are process-local; filesystem preflights are not a hostile-race sandbox. */
public class ReplayFilesService {
    private static final Logger LOGGER = LogManager.getLogger();
    private final ReplayFoldersService folders;
    private final Map<String, Object> locks = new HashMap<>();
    private final Map<String, Path> lockPaths = new HashMap<>();
    private final Map<String, Stamp> generated = new HashMap<>();
    private final Map<String, Path> temps = new HashMap<>();
    private final Map<String, Recovery> recoveries = new LinkedHashMap<>();

    public ReplayFilesService(ReplayFoldersService folders) { this.folders = folders; }

    private synchronized void acquire(Object owner, Path... paths) throws IOException {
        for (Path p : paths) {
            if (p == null) continue;
            Object other = locks.get(identity(p));
            if (other != null && other != owner) throw new FileLockedException(p);
            checkPath(p);
            if (exists(p)) {
                for (Map.Entry<String, Path> entry : lockPaths.entrySet()) {
                    if (locks.get(entry.getKey()) != owner && exists(entry.getValue())) {
                        checkPath(entry.getValue());
                        if (Files.isSameFile(p, entry.getValue())) throw new FileLockedException(p);
                    }
                }
            }
        }
        for (Path p : paths) if (p != null) {
            locks.put(identity(p), owner); lockPaths.put(identity(p), p);
        }
    }
    private synchronized void release(Object owner) {
        locks.values().removeIf(value -> value == owner);
        lockPaths.keySet().retainAll(locks.keySet());
    }
    private synchronized boolean locked(Path path) { return locks.containsKey(identity(path)); }

    public ReplayFile open(Path path) throws IOException {
        Path p = folders.absolute(path);
        synchronized (this) { if (recoveries.containsKey(identity(p))) return openRecovery(p); }
        // A timestamp collision in the work root is NOT permission to edit a previous recording.
        if (p.startsWith(folders.recordingRoot())) return open(null, p);
        return open(exists(p) ? p : null, p);
    }

    public ReplayFile open(Path input, Path output) throws IOException {
        Path in = input == null ? null : folders.absolute(input);
        Path out = folders.absolute(Objects.requireNonNull(output, "output"));
        Object owner = new Object();
        acquire(owner, in, out);
        Session session = null;
        try {
            checkPath(out);
            boolean update = in != null && identity(in).equals(identity(out));
            if (in != null) { regular(in); validateReplay(in); }
            if (!update && exists(out)) throw new FileAlreadyExistsException(out.toString());
            session = newSession(owner, in, out, update ? Stamp.read(out) : null);
            ReplayFile delegate = newDelegate(session, in);
            return ManagedReplayFile.create(this, session, delegate);
        } catch (IOException | RuntimeException e) {
            if (session == null || !session.constructionUncertain) release(owner);
            else warn("Library constructor failed after opening may have begun; locks retained until restart: " + session.workspace, e);
            throw e;
        }
    }

    Path absolute(Path path) { return folders.absolute(path); }
    Path cachePath(Session session) throws IOException { return folders.getCachePathForReplay(session.logicalOutput); }
    void export(Session session, Path snapshot, Path target) throws IOException {
        String hash = Stamp.read(snapshot).hash;
        publishCopy(snapshot, target, session.owner);
        session.preservedTarget = target;
        session.preservedHash = hash;
    }

    private Session newSession(Object owner, Path in, Path out, Stamp expected) throws IOException {
        Properties extra = new Properties();
        extra.setProperty("logicalOutput", out.toString());
        extra.setProperty("logicalInput", in == null ? "" : in.toString());
        Path workspace = ownedDirectory(folders.getRecordingFolder(), "work", extra);
        Session s = new Session(owner, in, out, workspace, expected);
        s.record("OPEN_DIRTY");
        return s;
    }

    final class Session {
        final Object owner;
        final Path logicalInput, logicalOutput, workspace;
        Stamp expected, baselineStamp;
        Path generation, baseline, snapshot, preservedTarget;
        String preservedHash;
        long sequence;
        boolean cleanupPending, constructionUncertain;
        Session(Object owner, Path input, Path output, Path workspace, Stamp expected) {
            this.owner = owner; this.logicalInput = input; this.logicalOutput = output;
            this.workspace = workspace; this.expected = expected;
        }
        void record(String state) throws IOException {
            readOwnership(workspace, "work");
            Properties p = new Properties();
            p.setProperty("schema", SCHEMA);
            p.setProperty("state", state);
            p.setProperty("logicalOutput", logicalOutput.toString());
            p.setProperty("generation", generation == null ? "" : generation.getFileName().toString());
            p.setProperty("baseline", baseline == null ? "" : baseline.toString());
            if (baselineStamp != null) p.setProperty("baselineHash", baselineStamp.hash);
            p.setProperty("snapshot", snapshot == null ? "" : snapshot.getFileName().toString());
            if (snapshot != null) p.setProperty("snapshotHash", Stamp.read(snapshot).hash);
            p.setProperty("cleanupPending", Boolean.toString(cleanupPending));
            writeProperties(workspace.resolve(String.format(Locale.ROOT, "state-%020d.properties", ++sequence)), p);
        }
    }

    ReplayFile newDelegate(Session s, Path input) throws IOException {
        if (s.constructionUncertain) throw new IOException("A prior library constructor may still own handles; restart required: " + s.workspace);
        readOwnership(s.workspace, "work");
        checkTree(s.workspace);
        if (input != null) { regular(input); validateReplay(input); }
        Path generation = s.workspace.resolve("generation-" + UUID.randomUUID() + ".mcpr");
        s.generation = generation;
        s.baseline = input;
        s.baselineStamp = input == null ? null : Stamp.read(input);
        s.record("OPEN_DIRTY");
        Path cache = folders.getCachePathForReplay(s.logicalOutput);
        checkTree(cache);
        s.constructionUncertain = true;
        ReplayFile result = new ZipReplayFile(new ReplayStudio(), input == null ? null : input.toFile(), generation.toFile(), cache.toFile());
        s.constructionUncertain = false;
        return result;
    }

    void dirty(Session s) throws IOException { s.record("OPEN_DIRTY"); }

    Path snapshot(Session s, ReplayFile delegate) throws IOException {
        readOwnership(s.workspace, "work");
        checkTree(s.workspace);
        Path candidate = s.workspace.resolve("snapshot-" + UUID.randomUUID() + ".mcpr");
        Files.createFile(candidate);
        // saveTo only targets an exclusively created, owned file, never the input or final output.
        if (s.baselineStamp != null) s.baselineStamp.verify(s.baseline);
        delegate.saveTo(candidate.toFile());
        if (s.baselineStamp != null) s.baselineStamp.verify(s.baseline);
        force(candidate);
        validateReplay(candidate);
        s.snapshot = candidate;
        s.record("COMPLETE_SNAPSHOT");
        return candidate;
    }

    void beforeDelegateClose(Session s) throws IOException {
        readOwnership(s.workspace, "work");
        checkTree(s.workspace);
        // The library recursively deletes tmp without NOFOLLOW checks; validate immediately before it.
        checkTree(folders.getCachePathForReplay(s.logicalOutput));
    }

    void publishSnapshot(Session s, Path snapshot) throws IOException {
        String hash = Stamp.read(snapshot).hash;
        if (s.expected == null) {
            publishCopy(snapshot, s.logicalOutput, s.owner);
        } else {
            try (Replacement replacement = replacement(s.logicalOutput, s.owner, s.expected, true)) {
                copyInto(snapshot, replacement.stagedPath());
                replacement.commit();
            }
        }
        s.preservedTarget = s.logicalOutput;
        s.preservedHash = hash;
        // Publication already happened. Receipt/cleanup failures must not masquerade as a failed publish.
        try { s.expected = Stamp.read(s.logicalOutput); rememberGenerated(s.logicalOutput, s.expected); }
        catch (IOException | RuntimeException e) { s.cleanupPending = true; warn("Published, but identity receipt could not be read: " + s.logicalOutput, e); }
        receipt(s, "PUBLISHED");
    }

    void receipt(Session s, String state) {
        try { s.record(state); }
        catch (IOException | RuntimeException e) { s.cleanupPending = true; warn("Published/preserved data, pending journal repair in " + s.workspace, e); }
    }

    void finish(Session s, boolean recovery) {
        try {
            if (!recovery && s.snapshot != null) {
                // An exported file may have been moved/deleted externally. That never licenses loss of our last snapshot.
                if (s.preservedTarget == null || !Stamp.read(s.preservedTarget).hash.equals(s.preservedHash)
                        || !Stamp.read(s.snapshot).hash.equals(s.preservedHash)) {
                    throw new IOException("The published/exported revision is no longer independently present");
                }
            }
            s.record(recovery ? "NEEDS_RECOVERY" : "CLOSED");
            if (!recovery) {
                readOwnership(s.workspace, "work");
                deleteTree(s.workspace);
            }
        } catch (IOException | RuntimeException e) {
            s.cleanupPending = true;
            warn("Resources closed; work/snapshot retained for recovery or cleanup: " + s.workspace, e);
            receipt(s, "NEEDS_RECOVERY");
        } finally {
            // The delegate really closed. Receipt/deletion failure is not an outstanding-handle failure.
            release(s.owner);
        }
    }

    private Path ownedDirectory(Path root, String kind, Properties extra) throws IOException {
        directory(root);
        String id = UUID.randomUUID().toString();
        Path dir = root.resolve(".replaymod-" + kind + "-" + id);
        Files.createDirectory(dir);
        Properties p = ownership(kind, id, dir);
        p.putAll(extra);
        writeProperties(dir.resolve(OWNER), p);
        return dir;
    }

    private static void component(String text) throws IOException {
        if (text == null || text.isBlank() || text.equals(".") || text.equals("..")
                || text.indexOf('/') >= 0 || text.indexOf('\\') >= 0 || text.indexOf(':') >= 0
                || text.indexOf('\0') >= 0) throw new IOException("Expected a single safe filename component: " + text);
    }

    public Path createTempFile(String prefix, String suffix) throws IOException {
        component(prefix);
        if (suffix == null) suffix = ".tmp";
        if (!suffix.isEmpty()) component(suffix);
        Path container = ownedDirectory(folders.getTempFolder(), "temp", new Properties());
        Path file = container.resolve(prefix + suffix);
        checkPath(file);
        Files.createFile(file);
        synchronized (this) { temps.put(identity(file), container); }
        return file;
    }

    public Path createTempDirectory(String prefix) throws IOException {
        component(prefix);
        Path container = ownedDirectory(folders.getTempFolder(), "temp", new Properties());
        Path dir = container.resolve(prefix);
        checkPath(dir);
        Files.createDirectory(dir);
        synchronized (this) { temps.put(identity(dir), container); }
        return dir;
    }

    public void deleteOwnedTemp(Path path) throws IOException {
        Path p = folders.absolute(path);
        Path container;
        synchronized (this) { container = temps.get(identity(p)); }
        if (container == null || !p.getParent().equals(container)) throw new IOException("Unknown temporary entry: " + p);
        readOwnership(container, "temp");
        checkTree(container);
        try (DirectoryStream<Path> children = Files.newDirectoryStream(container)) {
            for (Path child : children) {
                if (!child.equals(p) && !child.equals(container.resolve(OWNER))) {
                    throw new IOException("Unexpected sibling in owned temp container (left untouched): " + child);
                }
            }
        }
        // Do not delete siblings even inside a container if another component has put data there.
        if (exists(p)) deleteTree(p);
        Files.delete(container.resolve(OWNER));
        Files.delete(container);
        synchronized (this) { temps.remove(identity(p)); }
    }

    public Path publishReplay(Path source, Path target) throws IOException {
        Path src = folders.absolute(source), dst = folders.absolute(target);
        Object owner = new Object();
        acquire(owner, src, dst);
        try {
            regular(src);
            validateReplay(src);
            if (identity(src).equals(identity(dst))) return dst;
            Stamp sourceStamp = Stamp.read(src);
            publishCopy(src, dst, owner);
            // Only registered generated files can be removed. An imported/legacy source remains intact.
            Stamp registered;
            synchronized (this) { registered = generated.get(identity(src)); }
            if (registered != null && registered.equals(sourceStamp)) {
                try {
                    registered.verify(src);
                    if (!Stamp.read(dst).hash.equals(sourceStamp.hash)) throw new IOException("Published target changed; keeping source: " + src);
                    Files.delete(src);
                    synchronized (this) { generated.remove(identity(src)); }
                } catch (IOException | RuntimeException e) { pendingCleanup(src, dst, e); }
            }
            return dst;
        } finally { release(owner); }
    }

    private void publishCopy(Path source, Path target, Object owner) throws IOException {
        acquire(owner, target);
        checkPath(target);
        if (exists(target)) throw new FileAlreadyExistsException(target.toString());
        Stamp before = Stamp.read(source);
        Properties p = new Properties();
        p.setProperty("source", source.toString()); p.setProperty("target", target.toString());
        Path tx = ownedDirectory(directory(target.getParent()), "publish", p);
        Path part = Files.createFile(tx.resolve("candidate.part"));
        copyInto(source, part);
        before.verify(source);
        validateReplay(part);
        Stamp staged = Stamp.read(part);
        if (!before.hash.equals(staged.hash)) throw new IOException("Staging digest mismatch: " + part);
        Properties prepared = new Properties(); prepared.setProperty("hash", staged.hash);
        writeProperties(tx.resolve("prepared.properties"), prepared);
        noClobber(part, target);
        synchronized (this) { generated.put(identity(target), staged); }
        try {
            staged.verify(target);
            rememberGenerated(target, staged);
            Properties published = new Properties(); published.setProperty("target", target.toString()); published.setProperty("hash", staged.hash);
            writeProperties(tx.resolve("published.properties"), published);
            readOwnership(tx, "publish");
            staged.verify(target);
            deleteTree(tx);
        } catch (IOException | RuntimeException e) { pendingCleanup(tx, target, e); }
    }

    public void discardReplay(Path path) throws IOException {
        Path p = folders.absolute(path);
        Object owner = new Object();
        acquire(owner, p);
        try {
            Stamp stamp;
            synchronized (this) { stamp = generated.get(identity(p)); }
            if (stamp == null) throw new IOException("Not a registered generated replay: " + p);
            stamp.verify(p);
            Files.delete(p);
            synchronized (this) { generated.remove(identity(p)); }
        } finally { release(owner); }
    }

    public Replacement prepareReplacement(Path existingTarget) throws IOException {
        Path target = folders.absolute(existingTarget);
        Object owner = new Object();
        acquire(owner, target);
        try { return replacement(target, owner, Stamp.read(target), false); }
        catch (IOException | RuntimeException e) { release(owner); throw e; }
    }

    private Replacement replacement(Path target, Object owner, Stamp expected, boolean replay) throws IOException {
        acquire(owner, target);
        expected.verify(target);
        Properties p = new Properties();
        p.setProperty("target", target.toString()); p.setProperty("oldHash", expected.hash);
        p.setProperty("format", replay ? "replay" : "caller-validated");
        Path tx = ownedDirectory(directory(target.getParent()), "replacement", p);
        return new Replacement(this, owner, target, tx, expected, replay, !replay);
    }

    /** Exclusive target-volume candidate. Caller must close all media handles and validate format before commit. */
    public static final class Replacement implements AutoCloseable {
        private final ReplayFilesService service;
        private final Object owner;
        private final Path target, transaction, staged;
        private final Stamp expected;
        private final boolean replay, releaseLock;
        private boolean committed, closed;
        private Replacement(ReplayFilesService service, Object owner, Path target, Path tx, Stamp expected,
                            boolean replay, boolean releaseLock) throws IOException {
            this.service = service; this.owner = owner; this.target = target; this.transaction = tx;
            this.expected = expected; this.replay = replay; this.releaseLock = releaseLock;
            this.staged = Files.createFile(tx.resolve("candidate.part"));
        }
        public Path stagedPath() { return staged; }
        public synchronized void commit() throws IOException {
            if (committed) return;
            if (closed) throw new IOException("Replacement already closed: " + transaction);
            readOwnership(transaction, "replacement"); checkTree(transaction);
            regular(staged);
            if (Files.size(staged) == 0) throw new IOException("Empty replacement: " + staged);
            force(staged);
            if (replay) validateReplay(staged);
            Stamp candidate = Stamp.read(staged);
            expected.verify(target);
            Path backup = transaction.resolve("previous.backup");
            if (!exists(backup)) copyNew(target, backup);
            if (!Stamp.read(backup).hash.equals(expected.hash)) throw new IOException("Backup verification failed: " + backup);
            Properties prepared = new Properties(); prepared.setProperty("newHash", candidate.hash);
            prepared.setProperty("oldHash", expected.hash);
            writeProperties(transaction.resolve("prepared-" + UUID.randomUUID() + ".properties"), prepared);
            expected.verify(target);
            Path displaced = transaction.resolve("previous-" + UUID.randomUUID() + ".mcpr");
            // We own the opened identity, NOT an arbitrary current occupant. Backup and candidate exist first.
            noClobber(target, displaced);
            if (exists(target)) { expected.verify(target); Files.delete(target); } // hard-link provider case
            try {
                expected.verify(displaced);
                noClobber(staged, target);
            } catch (IOException failure) {
                try { if (!exists(target)) noClobber(displaced, target); }
                catch (IOException restore) { failure.addSuppressed(restore); }
                throw new IOException("Replacement not published; original/candidate retained in " + transaction, failure);
            }
            committed = true;
            // Everything after this line is cleanup/receipt, never a publication failure.
            try {
                candidate.verify(target);
                if (replay) service.rememberGenerated(target, candidate);
                Properties done = new Properties(); done.setProperty("target", target.toString()); done.setProperty("hash", candidate.hash);
                writeProperties(transaction.resolve("published.properties"), done);
                readOwnership(transaction, "replacement");
                candidate.verify(target);
                deleteTree(transaction);
            } catch (IOException | RuntimeException e) { service.pendingCleanup(transaction, target, e); }
        }
        @Override public synchronized void close() throws IOException {
            if (closed) return;
            // No unconditional deletion: uncommitted staged media and the old version are recovery material.
            IOException failure = null;
            if (!committed) {
                try {
                    Properties p = new Properties(); p.setProperty("target", target.toString());
                    writeProperties(transaction.resolve("needs-recovery-" + UUID.randomUUID() + ".properties"), p);
                } catch (IOException e) { failure = e; }
            }
            closed = true;
            if (releaseLock) service.release(owner);
            if (failure != null) throw new IOException("Replacement retained but recovery receipt failed: " + transaction, failure);
        }
    }

    /** Windows default provider uses MoveFileEx(flags=0), NOT ATOMIC_MOVE (which replaces). */
    private static void noClobber(Path source, Path target) throws IOException {
        regular(source); checkPath(target); checkPath(target.getParent());
        if (!Files.getFileStore(source).equals(Files.getFileStore(target.getParent()))) {
            throw new IOException("No-clobber publication requires target-volume staging");
        }
        if (windows(source) && source.getFileSystem().provider() == target.getFileSystem().provider()) {
            Files.move(source, target); // deliberately no options
        } else {
            try { Files.createLink(target, source); }
            catch (UnsupportedOperationException e) { throw new IOException("Provider lacks no-clobber hard-link publication", e); }
            // Keeping the owned part link avoids turning cleanup failure into publication failure.
        }
    }

    private synchronized void rememberGenerated(Path path, Stamp stamp) { generated.put(identity(path), stamp); }
    private void pendingCleanup(Path source, Path target, Exception error) {
        warn("PUBLISHED " + target + "; cleanup/receipt pending, preserved " + source, error);
        try {
            Properties p = new Properties(); p.setProperty("source", source.toString()); p.setProperty("target", target.toString());
            p.setProperty("state", "PUBLISHED_CLEANUP_PENDING");
            Path tx = ownedDirectory(directory(target.getParent()), "cleanup", p);
            LOGGER.warn("Cleanup receipt: {}", tx);
        } catch (IOException | RuntimeException secondary) { error.addSuppressed(secondary); LOGGER.warn("Could not persist cleanup receipt; source retained", secondary); }
    }
    static void warn(String message, Throwable error) { LOGGER.warn(message, error); }
    static boolean exists(Path path) { return Files.exists(path, LinkOption.NOFOLLOW_LINKS); }
    static void regular(Path path) throws IOException {
        checkPath(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular file: " + path);
    }
    static void copyNew(Path source, Path target) throws IOException {
        checkPath(target);
        Files.createFile(target);
        copyInto(source, target);
    }
    static void copyInto(Path source, Path target) throws IOException {
        regular(source); regular(target);
        try (InputStream in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
             OutputStream out = Files.newOutputStream(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                     LinkOption.NOFOLLOW_LINKS)) { in.transferTo(out); }
        force(target);
    }

    static record Stamp(String fileKey, long size, long modified, String hash) {
        static Stamp read(Path path) throws IOException {
            regular(path);
            BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            MessageDigest digest = digest();
            try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = new byte[65536];
                for (int n; (n = in.read(bytes)) >= 0;) if (n > 0) digest.update(bytes, 0, n);
            }
            BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())) throw new IOException("File changed while reading: " + path);
            return new Stamp(String.valueOf(after.fileKey()), after.size(), after.lastModifiedTime().toMillis(),
                    HexFormat.of().formatHex(digest.digest()));
        }
        void verify(Path path) throws IOException {
            if (!equals(read(path))) throw new IOException("Opened file identity changed: " + path);
        }
    }

    static void entryName(String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                || name.indexOf('\0') >= 0) throw new IOException("Unsafe replay entry: " + name);
        for (String part : name.split("/", -1)) {
            if (part.equals("..") || part.equals(".")) throw new IOException("Unsafe replay entry: " + name);
        }
    }

    /** ZIP structure, all entry sizes/CRCs, names and mandatory replay entries; not packet-protocol validation. */
    static void validateReplay(Path path) throws IOException {
        regular(path);
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[65536];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                entryName(entry.getName());
                if (!names.add(entry.getName())) throw new IOException("Duplicate ZIP entry: " + entry.getName());
                CRC32 crc = new CRC32(); long length = 0;
                try (InputStream in = zip.getInputStream(entry)) {
                    for (int n; (n = in.read(buffer)) >= 0;) if (n > 0) { crc.update(buffer, 0, n); length += n; }
                }
                if (length != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("Corrupt ZIP entry: " + entry.getName());
            }
        }
        if (!names.contains("recording.tmcpr") || !names.contains("metaData.json")) throw new IOException("Incomplete replay ZIP: " + path);
    }

    private record Recovery(Path key, Path logicalTarget, Path snapshot, Path tmp, Path baseline, boolean owned, String signature) { }

    public ReplayFile openRecovery(Path recoveryKey) throws IOException {
        Path key = folders.absolute(recoveryKey);
        Recovery recovery;
        synchronized (this) { recovery = recoveries.get(identity(key)); }
        if (recovery == null) throw new IOException("Unknown recovery key: " + key);
        Object owner = new Object();
        acquire(owner, key, recovery.baseline);
        Session s = null;
        try {
            if (!recoverySignature(key, recovery.baseline).equals(recovery.signature)) throw new IOException("Recovery source changed: " + key);
            String name = recovery.logicalTarget.getFileName().toString();
            if (name.endsWith(".mcpr")) name = name.substring(0, name.length() - 5);
            component(name);
            Path out = folders.getReplayFolder().resolve(name + "-recovered-" + UUID.randomUUID() + ".mcpr");
            acquire(owner, out);
            s = newSession(owner, null, out, null);
            Path baseline = recovery.snapshot != null ? recovery.snapshot : recovery.baseline;
            Path copied = null;
            if (baseline != null) {
                copied = s.workspace.resolve("recovery-base.mcpr");
                Stamp stamp = Stamp.read(baseline); copyNew(baseline, copied); stamp.verify(baseline);
                validateReplay(copied);
            }
            ReplayFile delegate;
            if (recovery.tmp == null) {
                if (!recoverySignature(key, recovery.baseline).equals(recovery.signature)) throw new IOException("Recovery changed while copying");
                delegate = newDelegate(s, copied);
            } else {
                s.generation = s.workspace.resolve("generation-" + UUID.randomUUID() + ".mcpr");
                s.baseline = copied;
                s.baselineStamp = copied == null ? null : Stamp.read(copied);
                Path tmp = Path.of(s.generation + ".tmp");
                Files.createDirectory(tmp);
                for (String part : List.of("changed", "removed")) {
                    Path source = recovery.tmp.resolve(part);
                    if (exists(source)) copyTree(source, tmp.resolve(part));
                }
                if (!recoverySignature(key, recovery.baseline).equals(recovery.signature)) throw new IOException("Recovery changed while copying");
                // Never copy the library's untrusted absolute 'source' pointer into the new tree.
                s.record("OPEN_DIRTY");
                checkTree(tmp);
                Path cache = folders.getCachePathForReplay(out);
                s.constructionUncertain = true;
                delegate = new ZipReplayFile(new ReplayStudio(), copied == null ? null : copied.toFile(),
                        s.generation.toFile(), cache.toFile());
                s.constructionUncertain = false;
            }
            return ManagedReplayFile.create(this, s, delegate);
        } catch (IOException | RuntimeException e) {
            if (s == null || !s.constructionUncertain) release(owner);
            else warn("Recovery constructor failed; possible private handles and locks retained until restart: " + s.workspace, e);
            throw e;
        }
    }

    /** Rejection/finished recovery is quarantine, NOT deletion; original key and bytes remain intact. */
    public void discardRecovery(Path recoveryKey) throws IOException {
        Path key = folders.absolute(recoveryKey);
        Recovery recovery;
        synchronized (this) { recovery = recoveries.get(identity(key)); }
        if (recovery == null) throw new IOException("Unknown recovery key: " + key);
        Object owner = new Object(); acquire(owner, key, recovery.baseline);
        try {
            String signature = recoverySignature(key, recovery.baseline);
            if (!signature.equals(recovery.signature)) throw new IOException("Recovery changed, not quarantined: " + key);
            Properties p = new Properties(); p.setProperty("recoveryKey", key.toString()); p.setProperty("signature", signature);
            p.setProperty("state", "QUARANTINED");
            ownedDirectory(folders.getRecordingFolder(), "quarantine", p);
            synchronized (this) { recoveries.remove(identity(key)); }
        } finally { release(owner); }
    }

    public void initialScan(ReplayMod core) {
        Set<Path> roots = new LinkedHashSet<>();
        try {
            roots.add(folders.recordingRoot()); roots.add(folders.replayRoot());
            roots.add(folders.gameDir().resolve("replay_recordings"));
            roots.add(folders.gameDir().resolve("replay_recordings/recording"));
        } catch (IOException e) { warn("Could not resolve recovery roots", e); return; }
        Map<String, String> quarantines = new HashMap<>();
        for (Path root : roots) scanQuarantines(root, quarantines);
        Set<String> seen = new HashSet<>();
        for (Path root : roots) {
            try {
                checkPath(root);
                if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) continue;
                try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
                    for (Path child : children) {
                        try {
                            if (!seen.add(identity(child)) || locked(child)) continue;
                            if (root.equals(folders.recordingRoot())
                                    && child.getFileName().toString().endsWith(".mcpr")
                                    && Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                                Path target = folders.getReplayFolder().resolve(child.getFileName());
                                if (!exists(target)) publishReplay(child, target);
                                // Legacy/unknown sources are copied, never adopted as deletion authority.
                                continue;
                            }
                            Recovery recovery = discover(child);
                            if (recovery == null || recovery.signature.equals(quarantines.get(identity(child)))) continue;
                            synchronized (this) { recoveries.put(identity(child), recovery); }
                            new RestoreReplayGui(core, GuiScreen.wrap(core.getMinecraft().currentScreen), child.toFile()).display();
                        } catch (IOException | RuntimeException e) { warn("Recovery entry preserved for manual inspection: " + child, e); }
                    }
                }
            } catch (IOException e) { warn("Recovery root unavailable; left untouched: " + root, e); }
        }
        new Thread(this::cleanup, "replaymod-cleanup").start();
    }

    private void scanQuarantines(Path root, Map<String, String> quarantines) {
        try {
            checkPath(root);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
            try (DirectoryStream<Path> children = Files.newDirectoryStream(root, ".replaymod-quarantine-*")) {
                for (Path child : children) {
                    try {
                        Properties p = readOwnership(child, "quarantine");
                        Path key = absoluteMetadataPath(p.getProperty("recoveryKey"));
                        quarantines.put(identity(key), p.getProperty("signature", ""));
                    } catch (IOException e) { warn("Invalid quarantine receipt left untouched: " + child, e); }
                }
            }
        } catch (IOException e) { warn("Quarantine scan unavailable: " + root, e); }
    }

    private Recovery discover(Path key) throws IOException {
        checkPath(key);
        if (!Files.isDirectory(key, LinkOption.NOFOLLOW_LINKS)) return null;
        String name = key.getFileName().toString();
        Path snapshot = null, tmp = null, baseline = null, logicalTarget;
        boolean owned = name.startsWith(".replaymod-work-");
        if (owned) {
            Properties owner = readOwnership(key, "work");
            Path output = absoluteMetadataPath(owner.getProperty("logicalOutput"));
            logicalTarget = output;
            if (locked(output)) return null;
            Properties state = latestState(key);
            if (state == null) return null;
            String status = state.getProperty("state");
            if ("CLOSED".equals(status)) return null;
            if (!output.equals(absoluteMetadataPath(state.getProperty("logicalOutput")))) throw new IOException("Mismatched work output");
            String generation = state.getProperty("generation", "");
            String snapshotName = state.getProperty("snapshot", "");
            if (!"OPEN_DIRTY".equals(status) && !snapshotName.isEmpty()) {
                component(snapshotName); snapshot = key.resolve(snapshotName); validateReplay(snapshot);
                if (!Stamp.read(snapshot).hash.equals(state.getProperty("snapshotHash"))) throw new IOException("Recovery snapshot changed");
            } else {
                if (generation.isEmpty()) return null;
                component(generation); tmp = key.resolve(generation + ".tmp");
                String input = state.getProperty("baseline", "");
                if (!input.isEmpty()) {
                    baseline = absoluteMetadataPath(input); validateReplay(baseline);
                    if (!Stamp.read(baseline).hash.equals(state.getProperty("baselineHash"))) {
                        throw new IOException("Recovery baseline changed: " + baseline);
                    }
                }
                if (!Files.isDirectory(tmp, LinkOption.NOFOLLOW_LINKS)) {
                    // Crash between reopening the complete snapshot and its next state receipt.
                    if (baseline != null && baseline.getParent().equals(key)
                            && baseline.getFileName().toString().equals(snapshotName)) {
                        snapshot = baseline; tmp = null;
                    } else return null;
                }
            }
        } else if (name.endsWith(".mcpr.tmp")) {
            logicalTarget = key.resolveSibling(name.substring(0, name.length() - 4));
            tmp = key;
            Path pointer = tmp.resolve("source");
            if (exists(pointer)) {
                regular(pointer);
                if (Files.size(pointer) > 32768) throw new IOException("Oversized legacy source pointer");
                baseline = absoluteMetadataPath(Files.readString(pointer));
                validateReplay(baseline);
            } else {
                Path original = key.resolveSibling(name.substring(0, name.length() - 4));
                if (exists(original)) { validateReplay(original); baseline = original; }
            }
        } else return null;
        checkTree(key);
        if (tmp != null) {
            if (!exists(tmp.resolve("changed/metaData.json")) && baseline == null) throw new IOException("Recovery has no metadata");
            if (!exists(tmp.resolve("changed/recording.tmcpr")) && baseline == null) throw new IOException("Recovery has no recording data");
            for (String sub : List.of("changed", "removed")) {
                Path dir = tmp.resolve(sub);
                if (exists(dir)) checkTree(dir);
            }
        }
        if (logicalTarget.getFileName() == null) throw new IOException("Recovery target has no file name");
        return new Recovery(key, logicalTarget, snapshot, tmp, baseline, owned, recoverySignature(key, baseline));
    }

    private static Properties latestState(Path workspace) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(workspace, "state-*.properties")) {
            for (Path child : children) if (child.getFileName().toString().matches("state-[0-9]{20}\\.properties")) files.add(child);
        }
        files.sort(Comparator.reverseOrder());
        if (files.isEmpty()) return null;
        // A torn latest receipt is not permission to fall back to a possibly stale complete snapshot.
        Properties p = readProperties(files.getFirst());
        if (!SCHEMA.equals(p.getProperty("schema"))
                || !Set.of("OPEN_DIRTY", "COMPLETE_SNAPSHOT", "PUBLISHED", "NEEDS_RECOVERY", "CLOSED", "EXPORTED")
                        .contains(p.getProperty("state", ""))
                || p.getProperty("generation") == null || p.getProperty("baseline") == null
                || p.getProperty("snapshot") == null || p.getProperty("logicalOutput") == null) {
            throw new IOException("Incomplete/unknown work receipt: " + files.getFirst());
        }
        return p;
    }
    private static Path absoluteMetadataPath(String value) throws IOException {
        if (value == null) throw new IOException("Missing metadata path");
        try {
            Path p = Path.of(value);
            if (!p.isAbsolute() || !p.equals(p.normalize())) throw new IOException("Non-normalized metadata path");
            checkPath(p); return p;
        } catch (InvalidPathException e) { throw new IOException("Invalid metadata path", e); }
    }
    private static void copyTree(Path source, Path target) throws IOException {
        checkTree(source);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                if (!relative.toString().isEmpty()) entryName(relative.toString().replace('\\', '/'));
                Files.createDirectory(target.resolve(relative)); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file); entryName(relative.toString().replace('\\', '/'));
                Stamp before = Stamp.read(file); Path copy = target.resolve(relative); copyNew(file, copy); before.verify(file);
                if (!before.hash.equals(Stamp.read(copy).hash)) throw new IOException("Recovery copy changed");
                return FileVisitResult.CONTINUE;
            }
        });
    }
    private static String recoverySignature(Path key, Path baseline) throws IOException {
        return hash(treeSignature(key) + (baseline == null ? "" : "\n" + identity(baseline) + "\n" + Stamp.read(baseline).hash));
    }
    private static String treeSignature(Path root) throws IOException {
        checkTree(root);
        List<Path> paths;
        try (var stream = Files.walk(root)) { paths = stream.sorted().toList(); }
        MessageDigest digest = digest();
        for (Path path : paths) {
            digest.update(root.relativize(path).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) digest.update(Stamp.read(path).hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void cleanup() {
        // No raw, .del, .no_recover, legacy-cache, work or configured-root recursive deletion.
        try {
            Path root = folders.cacheRoot(); checkPath(root);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
            try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
                for (Path cache : children) {
                    try {
                        Path replay = folders.getReplayPathForCache(cache);
                        synchronized (this) {
                            if (locked(replay)) continue;
                            checkTree(cache);
                            if (!exists(replay)) deleteTree(cache);
                        }
                    } catch (IOException | RuntimeException e) { LOGGER.debug("Cache left untouched: {} ({})", cache, e.toString()); }
                }
            }
        } catch (IOException e) { warn("Owned cache cleanup unavailable", e); }
    }

    public static class FileLockedException extends IOException {
        public FileLockedException(Path path) { super(path.toString()); }
    }
}
