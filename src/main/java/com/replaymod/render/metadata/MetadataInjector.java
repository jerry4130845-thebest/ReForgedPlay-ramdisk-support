package com.replaymod.render.metadata;

import com.coremedia.iso.IsoFile;
import com.coremedia.iso.boxes.*;
import com.google.common.primitives.Bytes;
import com.googlecode.mp4parser.DirectFileReadDataSource;
import com.replaymod.core.ReplayMod;
import com.replaymod.core.files.ReplayFilesService;
import com.replaymod.render.RenderSettings;
import de.johni0702.minecraft.gui.utils.lwjgl.Dimension;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.replaymod.render.ReplayModRender.LOGGER;

public class MetadataInjector {

    private static final String STITCHING_SOFTWARE = "Minecraft ReplayMod";

    /*
     * The Spherical XML is a modified version of https://github.com/google/spatial-media/tree/master/360-Videos-Metadata
     */
    private static final String SPHERICAL_XML_HEADER =
            "<?xml version=\"1.0\"?> " +
            "<rdf:SphericalVideo xmlns:rdf=" +
            "\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xmlns:GSpherical=\"http://ns.google.com/videos/1.0/spherical/\"> ";
    private static final String SPHERICAL_XML_CONTENTS =
            "<GSpherical:Spherical>true</GSpherical:Spherical> " +
            "<GSpherical:Stitched>true</GSpherical:Stitched> " +
            "<GSpherical:StitchingSoftware>"+STITCHING_SOFTWARE+"</GSpherical:StitchingSoftware> " +
            "<GSpherical:ProjectionType>equirectangular</GSpherical:ProjectionType> ";
    private static final String SPHERICAL_CROP_XML =
            "<GSpherical:FullPanoWidthPixels>%d</GSpherical:FullPanoWidthPixels> " +
            "<GSpherical:FullPanoHeightPixels>%d</GSpherical:FullPanoHeightPixels> " +
            "<GSpherical:CroppedAreaImageWidthPixels>%d</GSpherical:CroppedAreaImageWidthPixels> " +
            "<GSpherical:CroppedAreaImageHeightPixels>%d</GSpherical:CroppedAreaImageHeightPixels> " +
            "<GSpherical:CroppedAreaLeftPixels>%d</GSpherical:CroppedAreaLeftPixels> " +
            "<GSpherical:CroppedAreaTopPixels>%d</GSpherical:CroppedAreaTopPixels> ";
    private static final String STEREO_XML_CONTENTS = "<GSpherical:StereoMode>top-bottom</GSpherical:StereoMode>";
    private static final String SPHERICAL_XML_FOOTER = "</rdf:SphericalVideo>";

    private static final String XML_MONO_METADATA = SPHERICAL_XML_HEADER + SPHERICAL_XML_CONTENTS
            + SPHERICAL_CROP_XML + SPHERICAL_XML_FOOTER;
    private static final String XML_STEREO_METADATA = SPHERICAL_XML_HEADER + SPHERICAL_XML_CONTENTS
            + SPHERICAL_CROP_XML + STEREO_XML_CONTENTS + SPHERICAL_XML_FOOTER;

    /**
     * These bytes are taken from the variable 'spherical_uuid_id'
     * in https://github.com/google/spatial-media/tree/master/360-Videos-Metadata
     */
    private static final byte[] UUID_BYTES = new byte[] {
            (byte)0xff, (byte)0xcc, (byte)0x82, (byte)0x63,
            (byte)0xf8, (byte)0x55, (byte)0x4a, (byte)0x93,
            (byte)0x88, (byte)0x14, (byte)0x58, (byte)0x7a,
            (byte)0x02, (byte)0x52, (byte)0x1f, (byte)0xdd
    };

    public static void injectMetadata(RenderSettings.RenderMethod renderMethod, File videoFile,
                                      int videoWidth, int videoHeight,
                                      int sphericalFovX, int sphericalFovY) {
        String xmlString;
        switch (renderMethod) {
            case EQUIRECTANGULAR:
                xmlString = XML_MONO_METADATA;
                break;
            case ODS:
                xmlString = XML_STEREO_METADATA;
                break;
            default:
                throw new IllegalArgumentException("Invalid render method");
        }

        Dimension original = getOriginalDimensions(videoWidth, videoHeight, sphericalFovX, sphericalFovY);
        writeMetadata(videoFile, String.format(xmlString,
                original.getWidth(), original.getHeight(), videoWidth, videoHeight,
                (original.getWidth() - videoWidth) / 2, (original.getHeight() - videoHeight) / 2));
    }

    private static Dimension getOriginalDimensions(int videoWidth, int videoHeight,
                                                   int sphericalFovX, int sphericalFovY) {
        if (sphericalFovX < 360) {
            videoWidth = Math.round(videoWidth * 360 / (float) sphericalFovX);
        }

        if (sphericalFovY < 180) {
            videoHeight = Math.round(videoHeight * 180 / (float) sphericalFovY);
        }

        return new Dimension(videoWidth, videoHeight);
    }

    private static void writeMetadata(File videoFile, String metadata) {
        byte[] xml = metadata.getBytes(StandardCharsets.UTF_8);
        // Keep the historical wire layout: UUID, XML, then sixteen trailing zero bytes.
        byte[] expectedPayload = Arrays.copyOf(xml, Math.addExact(xml.length, 16));
        try (ReplayFilesService.Replacement replacement = ReplayMod.instance.files.prepareReplacement(videoFile.toPath())) {
            Inspection before;
            List<Span> expectedLayout;
            long expectedLength;
            int oldTrackChildren;
            // Register DataSource ownership BEFORE constructing IsoFile (which can throw).
            // DirectFileReadDataSource.map reads bytes, not an OS mapping which outlives close on Windows.
            try (DirectFileReadDataSource source = new DirectFileReadDataSource(videoFile);
                 IsoFile iso = new IsoFile(source);
                 FileChannel original = FileChannel.open(videoFile.toPath(), StandardOpenOption.READ)) {
                before = inspect(iso, original);
                oldTrackChildren = before.track.getBoxes().size();
                long moovBefore = before.movie.getSize();
                UserBox userBox = new UserBox(new byte[0]);
                userBox.setData(Bytes.concat(UUID_BYTES, xml));
                before.track.addBox(userBox);
                long growth = Math.subtractExact(before.movie.getSize(), moovBefore);
                require(growth > 0, "Invalid moov size increase");
                for (Box box : iso.getBoxes()) {
                    if (box instanceof FreeBox free) {
                        ByteBuffer data = free.getData();
                        long remaining = Math.subtractExact((long) data.limit(), Math.min((long) data.limit(), growth));
                        data.limit(Math.toIntExact(remaining));
                        free.setData(data.slice());
                        break;
                    }
                }
                expectedLayout = predictLayout(iso, before.top);
                expectedLength = endOf(expectedLayout);
                // Only the target-volume, independently owned candidate is ever opened for output.
                try (FileChannel out = FileChannel.open(replacement.stagedPath(), StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    iso.getBox(out);
                    require(out.position() == expectedLength && out.size() == expectedLength, "Incomplete MP4 write");
                    out.force(true);
                }
            } // All original/serialization handles must close successfully before validation/commit.
            try (DirectFileReadDataSource source = new DirectFileReadDataSource(replacement.stagedPath().toFile());
                 IsoFile iso = new IsoFile(source);
                 FileChannel candidate = FileChannel.open(replacement.stagedPath(), StandardOpenOption.READ)) {
                Inspection after = inspect(iso, candidate);
                require(candidate.size() == expectedLength && after.top.equals(expectedLayout), "Candidate layout mismatch");
                require(before.trackOrdinal == after.trackOrdinal, "Metadata track changed");
                require(before.chunks.size() == after.chunks.size(), "Chunk table count changed");
                for (int i = 0; i < before.chunks.size(); i++) {
                    ChunkTable a = before.chunks.get(i), b = after.chunks.get(i);
                    require(a.path.equals(b.path) && a.type.equals(b.type) && Arrays.equals(a.offsets, b.offsets),
                            "Chunk offsets changed: " + a.path);
                }
                require(before.mediaHashes.size() == after.mediaHashes.size(), "Media count changed");
                for (int i = 0; i < before.mediaHashes.size(); i++) {
                    require(Arrays.equals(before.mediaHashes.get(i), after.mediaHashes.get(i)), "Media bytes changed");
                }
                List<Box> children = after.track.getBoxes();
                require(children.size() == oldTrackChildren + 1, "Metadata track child count mismatch");
                Box last = children.get(children.size() - 1);
                require(last instanceof UserBox, "Missing injected UUID box");
                UserBox uuid = (UserBox) last;
                require(Arrays.equals(uuid.getUserType(), UUID_BYTES) && Arrays.equals(uuid.getData(), expectedPayload),
                        "Injected UUID or legacy XML/trailing-zero payload mismatch");
            } // Checked close, including the validator's DataSource, precedes commit.
            replacement.commit();
        } catch (Exception e) {
            // Replacement retains the old version/candidate on failure; never remove its recovery material here.
            LOGGER.error("Spherical metadata was not injected; original video/replacement recovery material retained: " + videoFile, e);
        }
    }

    private record Span(String type, long offset, long size, int header) {
        long end() { return Math.addExact(offset, size); }
    }
    private record ChunkTable(String path, String type, long[] offsets) { }
    private record Inspection(List<Span> top, List<ChunkTable> chunks, List<byte[]> mediaHashes,
                              MovieBox movie, TrackBox track, int trackOrdinal) { }

    private static final Set<String> TOP_LEVEL = Set.of("ftyp", "moov", "mdat", "free", "skip", "wide");
    private static final Set<String> UNSUPPORTED = Set.of("moof", "mvex", "mfra", "tfra", "sidx", "ssix",
            "tfhd", "trun", "iloc", "stz2", "saio", "saiz", "senc", "sinf");

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private static long endOf(List<Span> spans) {
        return spans.isEmpty() ? 0 : spans.get(spans.size() - 1).end();
    }

    private static ByteBuffer readAt(FileChannel channel, long offset, int count) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(count);
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, offset + buffer.position());
            if (n <= 0) throw new IOException("Truncated/stalled MP4 read at " + offset);
        }
        return buffer.flip();
    }

    private static Span readSpan(FileChannel channel, long offset, long limit) throws IOException {
        require(offset >= 0 && limit - offset >= 8, "Incomplete MP4 box header");
        ByteBuffer header = readAt(channel, offset, 8);
        long size = Integer.toUnsignedLong(header.getInt());
        byte[] fourcc = new byte[4];
        header.get(fourcc);
        String type = new String(fourcc, StandardCharsets.ISO_8859_1);
        int headerSize = 8;
        if (size == 1) {
            require(limit - offset >= 16, "Incomplete extended box header");
            size = readAt(channel, offset + 8, 8).getLong();
            headerSize = 16;
        }
        // Extends-to-EOF boxes cannot be safely reserialized by this narrow injection path.
        if (type.equals("uuid")) headerSize += 16;
        require(size >= headerSize && size <= limit - offset, "Invalid/unsupported box boundary: " + type);
        return new Span(type, offset, size, headerSize);
    }

    private static Inspection inspect(IsoFile iso, FileChannel bytes) throws IOException {
        long length = bytes.size();
        List<Span> top = new ArrayList<>();
        for (long offset = 0; offset < length;) {
            Span span = readSpan(bytes, offset, length);
            require(TOP_LEVEL.contains(span.type), "Unsupported top-level box: " + span.type);
            top.add(span);
            offset = span.end();
        }
        require(!top.isEmpty() && endOf(top) == length, "Incomplete MP4 layout");
        List<Box> boxes = new ArrayList<>(iso.getBoxes()); // Force the whole lazy list, not just moov.
        require(boxes.size() == top.size(), "Parser did not consume the complete file");
        List<ChunkTable> chunks = new ArrayList<>();
        List<Span> media = new ArrayList<>();
        List<byte[]> hashes = new ArrayList<>();
        MovieBox movie = null;
        boolean fileType = false;
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            Span span = top.get(i);
            checkBox(box, span);
            if (box instanceof MovieBox moov) {
                require(movie == null, "Multiple moov boxes");
                movie = moov;
            }
            if (span.type.equals("ftyp")) fileType = true;
            if (span.type.equals("mdat")) {
                media.add(span);
                hashes.add(hashRange(bytes, span.offset, span.size));
            }
            inspectChildren(box, span, bytes, Integer.toString(i), chunks, 0);
            checkBox(box, span); // Parsing lazy tables must not silently change the serialization size.
        }
        require(fileType && movie != null && !media.isEmpty(), "Missing ftyp/moov/mdat");
        List<TrackBox> tracks = movie.getBoxes(TrackBox.class);
        require(!tracks.isEmpty(), "Missing movie tracks");
        TrackBox target = null;
        int targetOrdinal = -1;
        for (int i = 0; i < tracks.size(); i++) {
            TrackBox track = tracks.get(i);
            validateTrack(track);
            if (target == null && "vide".equals(track.getMediaBox().getHandlerBox().getHandlerType())) {
                target = track;
                targetOrdinal = i;
            }
        }
        require(target != null && chunks.size() == tracks.size(), "Missing video/chunk tables or unsupported table layout");
        for (ChunkTable table : chunks) {
            for (long offset : table.offsets) {
                boolean inside = false;
                for (Span mdat : media) {
                    if (offset >= mdat.offset + mdat.header && offset < mdat.end()) { inside = true; break; }
                }
                require(inside, "Chunk points outside media payload: " + table.path);
            }
        }
        return new Inspection(List.copyOf(top), chunks, hashes, movie, target, targetOrdinal);
    }

    private static void validateTrack(TrackBox track) throws IOException {
        require(track.getTrackHeaderBox() != null && track.getMediaBox() != null, "Missing track structure");
        MediaBox media = track.getMediaBox();
        require(media.getHandlerBox() != null && media.getMediaHeaderBox() != null
                && media.getMediaInformationBox() != null, "Missing media structure");
        SampleTableBox table = track.getSampleTableBox();
        require(table != null && table.getSampleDescriptionBox() != null && table.getSampleSizeBox() != null
                && table.getSampleToChunkBox() != null && table.getTimeToSampleBox() != null,
                "Unsupported/missing sample tables");
        require(table.getBoxes(ChunkOffsetBox.class).size() == 1, "Expected one stco/co64 table per track");
        List<DataReferenceBox> refs = media.getMediaInformationBox().getBoxes(DataReferenceBox.class, true);
        require(refs.size() == 1 && !refs.get(0).getBoxes().isEmpty(), "Missing data references");
        for (Box ref : refs.get(0).getBoxes()) {
            require(ref instanceof DataEntryUrlBox && ((DataEntryUrlBox) ref).getFlags() == 1,
                    "External/unsupported media data reference");
        }
        require(!table.getSampleDescriptionBox().getBoxes().isEmpty(), "Missing sample descriptions");
        for (Box entry : table.getSampleDescriptionBox().getBoxes()) {
            require(entry instanceof com.coremedia.iso.boxes.sampleentry.SampleEntry, "Unsupported sample description");
            int index = ((com.coremedia.iso.boxes.sampleentry.SampleEntry) entry).getDataReferenceIndex();
            require(index > 0 && index <= refs.get(0).getBoxes().size(), "Invalid sample data reference");
        }
    }

    private static void checkBox(Box box, Span span) throws IOException {
        require(box.getOffset() == span.offset && box.getSize() == span.size && box.getType().equals(span.type),
                "Parser/wire box layout mismatch: " + span.type);
    }

    private static void inspectChildren(Box box, Span span, FileChannel bytes, String path,
                                        List<ChunkTable> chunks, int depth) throws IOException {
        require(depth < 64 && !UNSUPPORTED.contains(box.getType()), "Unsupported nested MP4 structure: " + box.getType());
        if (box instanceof ChunkOffsetBox offsets) {
            require(box.getType().equals("stco") || box.getType().equals("co64"), "Unsupported chunk table");
            require(offsets.getVersion() == 0 && offsets.getFlags() == 0, "Unsupported chunk table version/flags");
            long[] values = offsets.getChunkOffsets().clone();
            int width = box.getType().equals("stco") ? 4 : 8;
            require(span.size == Math.addExact(span.header + 8L, Math.multiplyExact((long) values.length, width)),
                    "Chunk table length mismatch");
            chunks.add(new ChunkTable(path, box.getType(), values));
        }
        if (!(box instanceof Container container)) return;
        List<Box> children = new ArrayList<>(container.getBoxes());
        if (children.isEmpty()) return;
        long offset = children.get(0).getOffset();
        require(offset >= span.offset + span.header, "Child before parent payload");
        // Plain structural containers have no prefix before their first child.
        if (Set.of("moov", "trak", "mdia", "minf", "stbl", "dinf", "edts", "udta").contains(span.type)) {
            require(offset == span.offset + span.header, "Unparsed container prefix");
        }
        for (int i = 0; i < children.size(); i++) {
            Box child = children.get(i);
            Span childSpan = readSpan(bytes, offset, span.end());
            checkBox(child, childSpan);
            inspectChildren(child, childSpan, bytes, path + "/" + i, chunks, depth + 1);
            checkBox(child, childSpan);
            offset = childSpan.end();
        }
        require(offset == span.end(), "Unparsed/truncated container tail: " + span.type);
    }

    private static List<Span> predictLayout(IsoFile iso, List<Span> original) throws IOException {
        List<Span> predicted = new ArrayList<>();
        List<Box> boxes = iso.getBoxes();
        require(boxes.size() == original.size(), "Top-level box count changed");
        long offset = 0;
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            Span old = original.get(i);
            long size = box.getSize();
            require(size >= 8 && old.type.equals(box.getType()), "Top-level structure changed");
            if (old.type.equals("mdat")) {
                require(offset == old.offset && size == old.size, "Injection would relocate media; original retained");
            }
            int header = old.header;
            if (old.type.equals("moov") && size >= (1L << 32)) header = 16;
            predicted.add(new Span(old.type, offset, size, header));
            offset = Math.addExact(offset, size);
        }
        return List.copyOf(predicted);
    }

    private static byte[] hashRange(FileChannel channel, long offset, long count) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        long end = Math.addExact(offset, count);
        while (offset < end) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), end - offset));
            int n = channel.read(buffer, offset);
            if (n <= 0) throw new IOException("Truncated/stalled media payload");
            offset += n;
            buffer.flip();
            digest.update(buffer);
        }
        return digest.digest();
    }

}