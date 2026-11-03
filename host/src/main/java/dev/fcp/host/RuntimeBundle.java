package dev.fcp.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Extracts build-time indexed resources from the application's class loader, never a user ZIP. */
public final class RuntimeBundle implements AutoCloseable {
    private static final long FILE_LIMIT = 2L * 1024 * 1024;
    private static final long TOTAL_LIMIT = 8L * 1024 * 1024;
    private final Path root;
    private RuntimeBundle(Path root) { this.root = root; }
    public Path root() { return root; }
    public static RuntimeBundle extract(ClassLoader loader) throws IOException {
        String prefix = "fcp/runtime/";
        byte[] indexBytes;
        try (InputStream in = loader.getResourceAsStream(prefix + "bundle.sha256")) {
            if (in == null) throw new IOException("Missing packaged FCP runtime index");
            indexBytes = in.readNBytes(65537);
            if (indexBytes.length > 65536) throw new IOException("Oversized runtime index");
        }
        Path root = Files.createTempDirectory("fcp-runtime-");
        restrict(root, true);
        RuntimeBundle bundle = new RuntimeBundle(root);
        long total = 0;
        Set<String> seen = new HashSet<>();
        try {
            for (String line : new String(indexBytes, StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank()) continue;
                if (!line.matches("[a-f0-9]{64}  [A-Za-z0-9_./-]+")) throw new IOException("Malformed resource index");
                String expected = line.substring(0,64), name = line.substring(66);
                Path relative = Path.of(name);
                if (relative.isAbsolute() || name.startsWith("/") || name.contains("..") ||
                    !relative.normalize().toString().replace('\\','/').equals(name) || !seen.add(name))
                    throw new IOException("Invalid runtime resource path");
                Path target = root.resolve(relative).normalize();
                if (!target.startsWith(root)) throw new IOException("Resource escaped runtime directory");
                byte[] bytes;
                try (InputStream in = loader.getResourceAsStream(prefix + name)) {
                    if (in == null) throw new IOException("Missing runtime resource: " + name);
                    bytes = in.readNBytes((int)FILE_LIMIT + 1);
                }
                total += bytes.length;
                if (bytes.length > FILE_LIMIT || total > TOTAL_LIMIT) throw new IOException("Runtime resource quota exceeded");
                if (!digest(bytes).equals(expected)) throw new IOException("Runtime checksum mismatch: " + name);
                Files.createDirectories(target.getParent());
                Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
                restrict(target, false);
            }
            if (!seen.contains("apps/launchers.json") || !seen.contains("plugins/statistics.py"))
                throw new IOException("Incomplete school runtime bundle");
            return bundle;
        } catch (IOException | RuntimeException e) {
            bundle.close(); throw e;
        }
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void restrict(Path path, boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE);
            if (directory) perms.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path,perms);
        }
    }
    @Override public void close() throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
