package fcp.state;

import fcp.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;
import static fcp.Schema.*;

/** Durable SINGLE-host store. The directory must be exclusively owned by the runtime user.
 * An OS file lock excludes a second writer. POSIX uses atomic replacement/fsync;
 * Windows uses the pinned SQLite runtime with durable transactions. Unsupported
 * durability fails closed. Production RuoYi uses the MySQL JDBC adapter instead.
 */
public final class FilePluginStateStore implements PluginStateStore, AutoCloseable {
    private final Path directory, file;
    private final FileChannel lockChannel;
    private final FileLock fileLock;
    // On POSIX, closing a second descriptor can release the first descriptor's process lock.
    // Reserve the inode in this JVM BEFORE opening another channel, including path aliases.
    private static final Set<Object> OPEN_LOCKS = ConcurrentHashMap.newKeySet();
    private final Object lockIdentity;
    private Map<Key, State> states = new LinkedHashMap<>();
    private final int capacity;
    private boolean closed, poisoned;
    private SqliteStateBackend sqlite;
    public FilePluginStateStore(Path directory, int capacity) throws IOException {
        this(directory,capacity,false);
    }
    private FilePluginStateStore(Path directory, int capacity, boolean migration) throws IOException {
        if (capacity < 1 || capacity > 10000) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
        this.directory = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(this.directory)) throw new IOException("Symlink state directory rejected");
        Files.createDirectories(this.directory);
        if (Files.getFileStore(this.directory).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
        this.file = this.directory.resolve("plugin-state.json");
        Path lockPath = this.directory.resolve("plugin-state.lock");
        if (Files.isSymbolicLink(lockPath) || Files.isSymbolicLink(file)) throw new IOException("Symlink state file rejected");
        try { Files.createFile(lockPath); } catch (FileAlreadyExistsException alreadyCreated) { /* validate below */ }
        BasicFileAttributes attributes=Files.readAttributes(lockPath,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isRegularFile())throw new IOException("Invalid lock file");
        Object identity=attributes.fileKey()!=null?attributes.fileKey():lockPath.toRealPath();
        if(!OPEN_LOCKS.add(identity))throw new IOException("State store already open in this JVM");
        FileChannel channel = null;
        FileLock lock = null;
        try {
            channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            lock = channel.tryLock();
            if (lock == null) throw new IOException("State store is already open by another host");
            if (System.getProperty("os.name","").startsWith("Windows")) {
                sqlite=new SqliteStateBackend(this.directory,migration);
                states=sqlite.load(capacity);
                if (!migration && Files.exists(file,LinkOption.NOFOLLOW_LINKS)
                        && !legacyPlan(this.directory,capacity).equals(sqlite.legacyDigest()))
                    throw new IOException("Legacy JSON does not match an approved conversion");
            } else {
                if (migration) throw new IOException("Windows conversion requires Windows");
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) states=readLegacy(file,capacity);
            }
        } catch (IOException | RuntimeException failure) {
            if (sqlite != null) try { sqlite.close(); } catch(IOException cleanup) { failure.addSuppressed(cleanup); }
            if (lock != null) try { lock.release(); } catch(IOException cleanup) { failure.addSuppressed(cleanup); }
            if (channel != null) try { channel.close(); } catch(IOException cleanup) { failure.addSuppressed(cleanup); }
            OPEN_LOCKS.remove(identity);throw failure;
        }
        lockChannel = channel; fileLock = lock; lockIdentity=identity;
    }
    private void live() {
        if (closed || poisoned) throw new FcpException("STATE_UNAVAILABLE", "State store is closed or requires recovery");
    }
    private void persist(Map<Key,State> next) {
        if (sqlite!=null) {
            try { sqlite.persist(next,null);states=next; }
            catch(RuntimeException failure) { poisoned=true;throw failure; }
            return;
        }
        List<Object> rows = new ArrayList<>();
        next.forEach((key,state) -> rows.add(Json.obj("scope",key.scope(),"plugin",key.plugin(),"enabled",state.enabled(),"revision",state.revision())));
        byte[] bytes = Json.write(Json.obj("format",1,"states",rows)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path temp = null;
        try {
            temp = Files.createTempFile(directory,"state-",".tmp");
            if (Files.getFileStore(temp).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"));
            try (FileChannel output = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel parent = FileChannel.open(directory,StandardOpenOption.READ)) { parent.force(true); }
            states = next;
        } catch (IOException failure) {
            poisoned = true; // Rename may have committed: do not continue on an uncertain outcome.
            throw new FcpException("STATE_UNAVAILABLE","Durable state write failed; reopen and inspect storage",failure);
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { /* no semantic retry */ }
        }
    }
    public synchronized State getOrCreate(Key key, boolean initialEnabled) {
        live(); State state=states.get(key);
        if (state == null) {
            if (states.size() >= capacity) throw new FcpException("RESOURCE_LIMIT","Plugin state capacity reached");
            state=new State(initialEnabled,1); Map<Key,State> next=new LinkedHashMap<>(states); next.put(key,state); persist(next);
        }
        return state;
    }
    public synchronized State compareAndSet(Key key,long expectedRevision,boolean enabled,String actor) {
        live(); if (actor == null || actor.isBlank() || actor.length() > 100) throw new IllegalArgumentException("actor");
        State old=states.get(key);
        if (old == null || old.revision()!=expectedRevision) throw new FcpException("STALE_COMPOSITION","Persisted plugin revision changed");
        State nextState=new State(enabled,Math.addExact(old.revision(),1));
        Map<Key,State> next=new LinkedHashMap<>(states); next.put(key,nextState); persist(next); return nextState;
    }
    public synchronized void close() throws IOException {
        if (!closed) {
            closed=true;
            try { try { if(sqlite!=null)sqlite.close(); } finally { try { fileLock.release(); } finally { lockChannel.close(); } } }
            finally { OPEN_LOCKS.remove(lockIdentity); }
        }
    }

    private static Map<Key,State> readLegacy(Path file,int capacity) throws IOException {
        if (!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>4_194_304)
            throw new IOException("Invalid state file");
        try {
            Map<String,Object> doc=map(Json.parse(Files.readString(file)));
            if (!doc.keySet().equals(Set.of("format","states")) || number(doc.get("format"))!=1)
                throw new IOException("Unsupported state format");
            Map<Key,State> result=new LinkedHashMap<>();
            for(Object value:arr(doc,"states")) {
                Map<String,Object> row=map(value);
                if(!row.keySet().equals(Set.of("scope","plugin","enabled","revision")) || !(row.get("enabled") instanceof Boolean))
                    throw new IOException("Invalid state row");
                Key key=new Key(str(row.get("scope")),str(row.get("plugin")));
                State state=new State((Boolean)row.get("enabled"),number(row.get("revision")));
                if(result.putIfAbsent(key,state)!=null || result.size()>capacity)throw new IOException("Invalid state cardinality");
            }
            return result;
        } catch(RuntimeException failure) {throw new IOException("Invalid legacy state",failure);}
    }
    public static String legacyPlan(Path directory,int capacity) throws IOException {
        if(capacity<1 || capacity>10000)throw new IllegalArgumentException("capacity");
        Path file=directory.resolve("plugin-state.json");
        readLegacy(file,capacity);
        return digest(Files.readAllBytes(file));
    }
    private static String digest(byte[] bytes) {
        try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
    }
    /** Explicit, restartable conversion. Original JSON and checked backup are retained. */
    public static void migrateLegacy(Path directory,int capacity,String expectedSha256) throws IOException {
        if(expectedSha256==null || !expectedSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("expectedSha256");
        if(!legacyPlan(directory,capacity).equals(expectedSha256))throw new IOException("Legacy conversion plan changed");
        try(var store=new FilePluginStateStore(directory,capacity,true)) {
            if(store.sqlite==null)throw new IOException("Windows conversion requires Windows");
            byte[] original=Files.readAllBytes(store.file);
            if(!digest(original).equals(expectedSha256))throw new IOException("Legacy conversion plan changed under lock");
            Map<Key,State> source=readLegacy(store.file,capacity);
            Path backup=store.directory.resolve("plugin-state.json.backup-"+expectedSha256);
            if(Files.isSymbolicLink(backup))throw new IOException("Symlink backup rejected");
            if(!Files.exists(backup)) {
                try(FileChannel output=FileChannel.open(backup,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                    ByteBuffer buffer=ByteBuffer.wrap(original);while(buffer.hasRemaining())output.write(buffer);output.force(true);
                }
            }
            if(!digest(Files.readAllBytes(backup)).equals(expectedSha256))throw new IOException("Legacy backup mismatch");
            String previous=store.sqlite.legacyDigest();
            if(previous!=null) {
                if(!previous.equals(expectedSha256))throw new IOException("Different conversion already recorded");
                // A previously committed conversion is idempotent, including later legitimate mutations.
                return;
            }
            if(!store.states.isEmpty())throw new IOException("Cannot overwrite existing SQLite state");
            store.sqlite.persist(source,expectedSha256);
            if(!store.sqlite.load(capacity).equals(source))throw new IOException("Converted state parity failed");
            if(!digest(Files.readAllBytes(store.file)).equals(expectedSha256))throw new IOException("Legacy source changed during conversion");
        }
    }
}
