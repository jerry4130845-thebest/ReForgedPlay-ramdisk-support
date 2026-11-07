package com.replaymod.core.files;

import com.replaymod.core.Setting;
import com.replaymod.core.SettingsRegistry;
import net.minecraft.client.MinecraftClient;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

<<<<<<< HEAD
/** Session-frozen locations. A configured root is never deletion authority. */
=======
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import static com.replaymod.core.utils.Utils.ensureDirectoryExists;

>>>>>>> f79ac15850486be1a46cfb477f99b85423b9d4ce
public class ReplayFoldersService {
    static final String OWNER = ".replaymod-owner.properties";
    static final String SCHEMA = "replaymod-storage-2";
    private final Path mcDir = MinecraftClient.getInstance().runDirectory.toPath().toAbsolutePath().normalize();
    private final String startupTemp = System.getProperty("java.io.tmpdir");
    private final SettingsRegistry settings;
<<<<<<< HEAD
    private Roots roots;

    public ReplayFoldersService(SettingsRegistry settings) { this.settings = settings; }
    private record Roots(Path replay, Path recording, Path raw, Path cache, Path temp, Path video) { }

    private Path resolve(String value) throws IOException {
=======
    private final Path tempDir;

    public ReplayFoldersService(SettingsRegistry settings) {
        this.settings = settings;
        this.tempDir = mcDir.resolve(settings.get(Setting.TEMP_PATH));
    }

    public Path getTempFolder() throws IOException {
        return ensureDirectoryExists(tempDir);
    }

    private static boolean isRamDisk(Path path) {
        String pathStr = path.toString().toLowerCase();
        if (pathStr.contains("/tmp")) return true;
        if (pathStr.contains("\\tmp")) return true;
        if (pathStr.contains("/dev/shm")) return true;
        if (pathStr.contains("\\dev\\shm")) return true;
        if (pathStr.contains("ramdisk")) return true;
        if (pathStr.contains("ram_disk")) return true;
        return false;
    }

    public Path getReplayFolder() throws IOException {
        return ensureDirectoryExists(mcDir.resolve(settings.get(Setting.RECORDING_PATH)));
    }

    public Path getRawReplayFolder() throws IOException {
        return ensureDirectoryExists(getReplayFolder().resolve("raw"));
    }

    public Path getRecordingFolder() throws IOException {
        return ensureDirectoryExists(getReplayFolder().resolve("recording"));
    }

    public Path getCacheFolder() throws IOException {
        Path path = ensureDirectoryExists(mcDir.resolve(settings.get(Setting.CACHE_PATH)));
>>>>>>> f79ac15850486be1a46cfb477f99b85423b9d4ce
        try {
            if (value == null || value.isBlank()) throw new IOException("Empty storage path");
            return absolute(Path.of(value));
        } catch (InvalidPathException e) { throw new IOException("Invalid storage path: " + value, e); }
    }

    private synchronized Roots roots() throws IOException {
        if (roots != null) return roots;
        // Construction precedes settings registration/loading. Do not read settings there.
        Path replay = resolve(settings.get(Setting.RECORDING_PATH));
        String work = settings.get(Setting.RECORDING_TEMP_PATH);
        String raw = settings.get(Setting.RAW_PATH);
        String temp = settings.get(Setting.TEMP_PATH);
        Roots candidate = new Roots(replay, work.isBlank() ? replay.resolve("recording") : resolve(work),
                raw.isBlank() ? replay.resolve("raw") : resolve(raw), resolve(settings.get(Setting.CACHE_PATH)),
                resolve(temp.isBlank() ? startupTemp : temp),
                resolve(settings.get(com.replaymod.render.Setting.RENDER_PATH)));
        Path[] paths = {candidate.replay, candidate.recording, candidate.raw, candidate.cache, candidate.temp, candidate.video};
        for (Path path : paths) {
            if (path.getParent() == null || mcDir.startsWith(path)
                    || path.equals(Path.of(System.getProperty("user.home")).toAbsolutePath().normalize())) {
                throw new IOException("Unsafe storage root: " + path);
            }
        }
        for (int i = 0; i < paths.length; i++) {
            for (int j = i + 1; j < paths.length; j++) {
                boolean nested = paths[i].startsWith(paths[j]) || paths[j].startsWith(paths[i]);
                boolean allowed = i == 0 && (j == 1 || j == 2)
                        && !paths[i].equals(paths[j]) && paths[j].startsWith(paths[i]);
                if (nested && !allowed) throw new IOException("Overlapping storage roots: " + paths[i] + " and " + paths[j]);
            }
        }
        // Freeze all six together, without probing/creating unrelated or offline volumes.
        return roots = candidate;
    }

    public Path getReplayFolder() throws IOException { return directory(roots().replay); }
    public Path getRecordingFolder() throws IOException { return directory(roots().recording); }
    public Path getRawReplayFolder() throws IOException { return directory(roots().raw); }
    public Path getCacheFolder() throws IOException { return directory(roots().cache); }
    public Path getTempFolder() throws IOException { return directory(roots().temp); }
    public Path getVideoFolder() throws IOException { return directory(roots().video); }
    Path recordingRoot() throws IOException { return roots().recording; }
    Path replayRoot() throws IOException { return roots().replay; }
    Path rawRoot() throws IOException { return roots().raw; }
    Path cacheRoot() throws IOException { return roots().cache; }
    Path videoRoot() throws IOException { return roots().video; }
    Path gameDir() { return mcDir; }
    Path absolute(Path path) { return (path.isAbsolute() ? path : mcDir.resolve(path)).toAbsolutePath().normalize(); }

    /** The library may delete its cache argument: metadata stays outside that data child. */
    public synchronized Path getCachePathForReplay(Path replay) throws IOException {
        Path logical = absolute(replay);
        checkPath(logical);
        String key = "v2-" + hash(identity(logical));
        Path parent = getCacheFolder().resolve(key);
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(parent);
            Properties owner = ownership("cache", key, parent);
            owner.setProperty("replay", logical.toString());
            owner.setProperty("identity", identity(logical));
            writeProperties(parent.resolve(OWNER), owner);
        }
        readCacheOwner(parent);
        checkTree(parent);
        return directory(parent.resolve("data"));
    }

    public Path getReplayPathForCache(Path cache) throws IOException {
        Path parent = absolute(cache);
        if (parent.getFileName().toString().equals("data")) parent = parent.getParent();
        return Path.of(readCacheOwner(parent).getProperty("replay"));
    }

    private Properties readCacheOwner(Path parent) throws IOException {
        if (!parent.getParent().equals(roots().cache)) throw new IOException("Cache outside frozen root: " + parent);
        Properties owner = readOwnership(parent, "cache");
        Path replay;
        try { replay = Path.of(owner.getProperty("replay", "")); }
        catch (InvalidPathException e) { throw new IOException("Invalid cache replay identity", e); }
        if (!replay.isAbsolute() || !replay.equals(replay.normalize())) throw new IOException("Non-absolute cache identity");
        String identity = identity(replay);
        if (!identity.equals(owner.getProperty("identity"))
                || !parent.getFileName().toString().equals("v2-" + hash(identity))) {
            throw new IOException("Unowned/old cache (left untouched): " + parent);
        }
        checkPath(replay);
        return owner;
    }

    static boolean windows(Path path) {
        return path.getFileSystem().provider().getClass().getName().equals("sun.nio.fs.WindowsFileSystemProvider");
    }
    static String identity(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        return windows(path) ? value.toLowerCase(Locale.ROOT) : value;
    }
    static String hash(String text) {
        return HexFormat.of().formatHex(digest().digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    /** Reject symlinks and Windows junction/reparse (isOther) entries at every existing ancestor. */
    static void checkPath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path part = absolute.getRoot();
        if (part == null) throw new IOException("No absolute root: " + path);
        if (windows(absolute)) {
            for (Path name : absolute) windowsComponent(name.toString());
        }
        checkNode(part);
        for (Path name : absolute) {
            part = part.resolve(name);
            try { checkNode(part); }
            catch (NoSuchFileException e) { return; }
        }
    }
    static void windowsComponent(String name) throws IOException {
        String upper = name.toUpperCase(Locale.ROOT);
        String base = upper.split("\\.", 2)[0];
        if (name.endsWith(".") || name.endsWith(" ") || name.indexOf(':') >= 0
                || base.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
            throw new IOException("Ambiguous/device Windows path component: " + name);
        }
    }
    private static void checkNode(Path path) throws IOException {
        BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (a.isSymbolicLink() || a.isOther() || (!a.isDirectory() && !a.isRegularFile())) {
            throw new IOException("Links/special files are not allowed: " + path);
        }
    }
    static Path directory(Path path) throws IOException {
        checkPath(path);
        Files.createDirectories(path);
        checkPath(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a directory: " + path);
        return path;
    }
    static void checkTree(Path path) throws IOException {
        checkPath(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                checkNode(dir); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                checkNode(file); return FileVisitResult.CONTINUE;
            }
        });
    }
    static Properties ownership(String kind, String id, Path path) {
        Properties p = new Properties();
        p.setProperty("schema", SCHEMA);
        p.setProperty("kind", kind);
        p.setProperty("id", id);
        p.setProperty("path", identity(path));
        return p;
    }
    static Properties readOwnership(Path path, String kind) throws IOException {
        checkPath(path);
        Properties p = readProperties(path.resolve(OWNER));
        if (!SCHEMA.equals(p.getProperty("schema")) || !kind.equals(p.getProperty("kind"))
                || !identity(path).equals(p.getProperty("path"))) throw new IOException("Not an owned " + kind + ": " + path);
        if (!kind.equals("cache")) {
            String id = p.getProperty("id", "");
            try { UUID.fromString(id); }
            catch (IllegalArgumentException e) { throw new IOException("Invalid ownership id", e); }
            if (!path.getFileName().toString().equals(".replaymod-" + kind + "-" + id)) {
                throw new IOException("Ownership/name mismatch: " + path);
            }
        } else if (!path.getFileName().toString().equals(p.getProperty("id"))) {
            throw new IOException("Cache ownership/name mismatch: " + path);
        }
        return p;
    }
    static Properties readProperties(Path file) throws IOException {
        checkPath(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 65536) {
            throw new IOException("Invalid storage metadata: " + file);
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { p.load(in); }
        catch (IllegalArgumentException e) { throw new IOException("Malformed storage metadata: " + file, e); }
        return p;
    }
    static void writeProperties(Path file, Properties p) throws IOException {
        checkPath(file);
        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) { p.store(out, "ReplayMod storage ownership / transaction receipt"); }
        force(file);
    }
    static void force(Path file) throws IOException {
        checkPath(file);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }
    /** Caller must first prove ownership of this exact subtree, never its configured root. */
    static void deleteTree(Path path) throws IOException {
        checkTree(path);
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                checkPath(dir); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                checkPath(file); Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                checkPath(dir); Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
}
