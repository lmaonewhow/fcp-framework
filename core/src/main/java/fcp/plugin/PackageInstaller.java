package fcp.plugin;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/** Bounded installer for trusted packages. Never executes code. Publisher names are NOT signatures:
 * the caller must authenticate the expected archive digest through its trusted distribution channel.
 * The store is single-node, owner-controlled and must not be writable by untrusted processes.
 * Read-only publication protects accidental changes, not a malicious process with the same OS uid.
 */
public final class PackageInstaller {
    public record Limits(long maxArchiveBytes,int maxEntries,long maxEntryBytes,long maxExpandedBytes,int maxCompressionRatio) {
        public Limits { if(maxArchiveBytes<1 || maxArchiveBytes>Integer.MAX_VALUE-8 || maxEntries<1 || maxEntries>65534 || maxEntryBytes<1 || maxExpandedBytes<maxEntryBytes || maxCompressionRatio<1) throw new IllegalArgumentException("Invalid package limits"); }
        public static Limits defaults() {return new Limits(64L*1024*1024,4096,32L*1024*1024,128L*1024*1024,200);}
    }
    public record InstalledPackage(PluginManifest manifest,String sha256,Path directory,boolean replayed) {}
    private record Entry(String name,long size,long compressed,long crc,long offset,boolean directory) {}
    private static final Object JVM_LOCK=new Object();
    private final Path root;
    private final Set<String> publishers;
    private final Limits limits;
    public PackageInstaller(Path root,Set<String> trustedPublishers,Limits limits) throws IOException {
        this.root=root.toAbsolutePath().normalize(); this.publishers=Set.copyOf(trustedPublishers);this.limits=Objects.requireNonNull(limits);
        safeDirectories(this.root);
        for(String d:List.of("packages","versions","requests","staging")) safeDirectories(this.root.resolve(d));
    }
    public PackageInstaller(Path root,Set<String> trustedPublishers) throws IOException {this(root,trustedPublishers,Limits.defaults());}
    public InstalledPackage install(Path archive,String expectedSha256,String idempotencyKey) throws IOException {
        if(expectedSha256==null || !expectedSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Expected lowercase SHA-256 required");
        if(idempotencyKey==null || idempotencyKey.isBlank() || idempotencyKey.length()>256) throw new IllegalArgumentException("Invalid idempotency key");
        synchronized(JVM_LOCK) {
            Path lock=root.resolve("install.lock"); requireNotLink(lock);
            try(FileChannel channel=FileChannel.open(lock,CREATE,WRITE,NOFOLLOW_LINKS); var ignored=channel.lock()) {
                return installLocked(archive,expectedSha256,idempotencyKey);
            }
        }
    }
    private InstalledPackage installLocked(Path source,String digest,String key) throws IOException {
        for(String d:List.of("packages","versions","requests","staging")) safeDirectories(root.resolve(d));
        Path request=root.resolve("requests").resolve(hash(key.getBytes(StandardCharsets.UTF_8))+".sha256");
        String prior=readRecord(request);
        if(prior!=null && !prior.equals(digest)) throw new IOException("Idempotency key conflict");
        Path work=Files.createTempDirectory(root.resolve("staging"),"install-");
        try {
            Path archive=work.resolve("archive.zip");
            MessageDigest md=sha(); long copied=0;
            try(InputStream in=Files.newInputStream(source,NOFOLLOW_LINKS);OutputStream out=Files.newOutputStream(archive,CREATE_NEW,WRITE)) {
                byte[] buf=new byte[8192]; int n;
                while((n=in.read(buf))!=-1) {copied+=n;if(copied>limits.maxArchiveBytes())throw new IOException("Archive size limit");md.update(buf,0,n);out.write(buf,0,n);}
            }
            if(!HexFormat.of().formatHex(md.digest()).equals(digest)) throw new IOException("Package digest mismatch");
            List<Entry> entries=inspect(Files.readAllBytes(archive));
            Path contents=Files.createDirectory(work.resolve("contents"));
            extract(archive,contents,entries);
            Path manifestFile=contents.resolve("plugin.json");
            if(!Files.isRegularFile(manifestFile,NOFOLLOW_LINKS) || Files.size(manifestFile)>1024*1024) throw new IOException("Missing or oversized plugin.json");
            PluginManifest manifest;
            try {manifest=PluginManifest.parse(decode(Files.readAllBytes(manifestFile)));} catch(IllegalArgumentException e) {throw new IOException("Invalid plugin manifest: "+e.getMessage(),e);}
            if(!publishers.contains(manifest.publisher())) throw new IOException("Publisher not allowed");
            Path entry=contents.resolve(manifest.runtime().entry());
            if(!Files.isRegularFile(entry,NOFOLLOW_LINKS)) throw new IOException("Runtime entry is not a package file");
            Path versionDir=root.resolve("versions").resolve(manifest.pluginId()); safeDirectories(versionDir);
            Path versionFile=versionDir.resolve(manifest.version()+".sha256");
            String versionDigest=readRecord(versionFile);
            if(versionDigest!=null && !versionDigest.equals(digest)) throw new IOException("Plugin version is immutable");
            Path published=root.resolve("packages").resolve(digest);
            boolean exists=Files.exists(published,NOFOLLOW_LINKS);
            if(exists) verifySame(contents,published);
            else {
                forceTree(contents);
                makeReadOnly(contents,manifest.runtime().entry());
                Files.setPosixFilePermissions(contents,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE));
                try {Files.move(contents,published,StandardCopyOption.ATOMIC_MOVE);} catch(AtomicMoveNotSupportedException e) {throw new IOException("Store requires atomic directory publication",e);}
            }
            makeReadOnly(published,manifest.runtime().entry());
            forceDirectory(published); forceDirectory(published.getParent());
            writeRecord(versionFile,digest); writeRecord(request,digest);
            return new InstalledPackage(manifest,digest,published,prior!=null);
        } finally {removeWork(work);}
    }
    private List<Entry> inspect(byte[] bytes) throws IOException {
        // Deliberately reject ZIP64, split archives, encryption and non-UTF-8 names.
        int eocd=-1;
        for(int i=bytes.length-22;i>=Math.max(0,bytes.length-65557);i--) if(u32(bytes,i)==0x06054b50L && i+22+u16(bytes,i+20)==bytes.length) {eocd=i;break;}
        if(eocd<0)throw new IOException("Missing ZIP end record");
        int count=u16(bytes,eocd+10);long centralSize=u32(bytes,eocd+12),centralOffset=u32(bytes,eocd+16);
        if(u16(bytes,eocd+4)!=0 || u16(bytes,eocd+6)!=0 || u16(bytes,eocd+8)!=count || count==65535 || count>limits.maxEntries() || centralOffset+centralSize!=eocd)throw new IOException("Unsupported ZIP layout or entry count");
        int p=(int)centralOffset; List<Entry> result=new ArrayList<>();Set<String> names=new HashSet<>();Map<String,String> allPaths=new HashMap<>();long total=0;
        List<long[]> spans=new ArrayList<>();
        for(int i=0;i<count;i++) {
            if(p<0 || (long)p+46>eocd || u32(bytes,p)!=0x02014b50L)throw new IOException("Invalid ZIP directory");
            int flags=u16(bytes,p+8),method=u16(bytes,p+10),nameLength=u16(bytes,p+28),extra=u16(bytes,p+30),comment=u16(bytes,p+32);
            long compressed=u32(bytes,p+20),size=u32(bytes,p+24),offset=u32(bytes,p+42),crc=u32(bytes,p+16);
            if((long)p+46+nameLength+extra+comment>eocd || compressed==0xffffffffL || size==0xffffffffL || offset==0xffffffffL || u16(bytes,p+34)!=0)throw new IOException("Unsupported ZIP64 or split entry");
            if((flags & ~0x080e)!=0 || (flags&1)!=0 || (method!=0 && method!=8))throw new IOException("Unsupported ZIP encoding/compression");
            int mode=(int)(u32(bytes,p+38)>>>16)&0170000;
            if(mode!=0 && mode!=0100000 && mode!=0040000)throw new IOException("ZIP links and special files forbidden");
            String name=decode(Arrays.copyOfRange(bytes,p+46,p+46+nameLength));boolean directory=name.endsWith("/");
            String canonical=directory?name.substring(0,name.length()-1):name;
            try {PluginManifest.relativePath(canonical);}catch(IllegalArgumentException e){throw new IOException("Unsafe ZIP path",e);}
            if(!names.add(canonical.toLowerCase(Locale.ROOT)))throw new IOException("Duplicate or case-alias ZIP path");
            String prefix=canonical;
            while(!prefix.isEmpty()) {
                String priorPath=allPaths.putIfAbsent(prefix.toLowerCase(Locale.ROOT),prefix);
                if(priorPath!=null && !priorPath.equals(prefix)) throw new IOException("Case-alias ZIP directory");
                if(allPaths.size()>limits.maxEntries()) throw new IOException("Expanded path count limit");
                int slash=prefix.lastIndexOf('/');prefix=slash<0?"":prefix.substring(0,slash);
            }
            if(directory && (size!=0 || (mode!=0 && mode!=0040000)))throw new IOException("Invalid directory entry");
            if(!directory && mode==0040000)throw new IOException("Directory mode/name mismatch");
            if(size>limits.maxEntryBytes() || size>limits.maxExpandedBytes()-total || size>(long)limits.maxCompressionRatio()*Math.max(1,compressed))throw new IOException("ZIP expansion limit");
            total+=size;
            if(offset+30>centralOffset || u32(bytes,(int)offset)!=0x04034b50L)throw new IOException("Invalid ZIP local header");
            int l=(int)offset,ln=u16(bytes,l+26),le=u16(bytes,l+28);
            long dataStart=offset+30+ln+le,end=dataStart+compressed;
            if(end>centralOffset || ln!=nameLength || u16(bytes,l+6)!=flags || u16(bytes,l+8)!=method || !Arrays.equals(Arrays.copyOfRange(bytes,l+30,l+30+ln),Arrays.copyOfRange(bytes,p+46,p+46+nameLength)))throw new IOException("ZIP header mismatch");
            spans.add(new long[]{offset,end});result.add(new Entry(name,size,compressed,crc,offset,directory));p+=46+nameLength+extra+comment;
        }
        if(p!=eocd)throw new IOException("Unexpected central directory data");
        spans.sort(Comparator.comparingLong(a->a[0])); long end=0;
        for(long[] span:spans){if(span[0]<end)throw new IOException("Overlapping ZIP entries");end=span[1];}
        for(Entry e:result) {String n=e.directory()?e.name().substring(0,e.name().length()-1):e.name();int slash=n.indexOf('/');while(slash>=0){String ancestor=n.substring(0,slash);for(Entry a:result)if(!a.directory() && a.name().equalsIgnoreCase(ancestor))throw new IOException("ZIP file/directory conflict");slash=n.indexOf('/',slash+1);}}
        return result;
    }
    private void extract(Path zip,Path contents,List<Entry> entries) throws IOException {
        long total=0;
        try(ZipFile file=new ZipFile(zip.toFile(),StandardCharsets.UTF_8)) {
            for(Entry e:entries) {
                Path target=contents.resolve(e.name()).normalize();if(!target.startsWith(contents))throw new IOException("ZIP path escape");
                if(e.directory()){Files.createDirectories(target);continue;}
                Files.createDirectories(target.getParent()); ZipEntry ze=file.getEntry(e.name());if(ze==null)throw new IOException("Missing ZIP entry");
                CRC32 crc=new CRC32();long count=0;
                try(InputStream in=file.getInputStream(ze);OutputStream out=Files.newOutputStream(target,CREATE_NEW,WRITE)) {
                    byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){count+=n;total+=n;if(count>e.size() || count>limits.maxEntryBytes() || total>limits.maxExpandedBytes())throw new IOException("Expanded size limit");crc.update(buffer,0,n);out.write(buffer,0,n);}
                }
                if(count!=e.size() || crc.getValue()!=e.crc())throw new IOException("ZIP size/CRC mismatch");
            }
        }
    }
    private static void verifySame(Path expected,Path actual) throws IOException {
        if(!Files.isDirectory(actual,NOFOLLOW_LINKS))throw new IOException("Invalid published package");
        Map<String,String> a=tree(expected),b=tree(actual);if(!a.equals(b))throw new IOException("Published package integrity failure");
    }
    private static Map<String,String> tree(Path root) throws IOException {
        Map<String,String> result=new TreeMap<>();try(var paths=Files.walk(root)){for(Path p:paths.toList()){if(p.equals(root))continue;requireNotLink(p);String key=root.relativize(p).toString();if(Files.isDirectory(p,NOFOLLOW_LINKS))result.put(key,"directory");else if(Files.isRegularFile(p,NOFOLLOW_LINKS)){MessageDigest md=sha();try(InputStream in=Files.newInputStream(p,NOFOLLOW_LINKS)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)md.update(buf,0,n);}result.put(key,HexFormat.of().formatHex(md.digest()));}else throw new IOException("Special file in package");}}return result;
    }
    private static void forceTree(Path root) throws IOException {
        try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) {
            if(Files.isDirectory(p,NOFOLLOW_LINKS)) forceDirectory(p);
            else try(FileChannel c=FileChannel.open(p,WRITE,NOFOLLOW_LINKS)){c.force(true);}
        }}
    }
    private static void forceDirectory(Path p) throws IOException {try(FileChannel c=FileChannel.open(p,READ,NOFOLLOW_LINKS)){c.force(true);}}
    private static void makeReadOnly(Path root,String executable) throws IOException {
        try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList()){
            if(Files.getFileStore(p).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(p,Files.isDirectory(p,NOFOLLOW_LINKS)||p.equals(root.resolve(executable))?Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_EXECUTE):Set.of(PosixFilePermission.OWNER_READ));
            else throw new IOException("Read-only package publication requires POSIX filesystem");
        }}
    }
    private static void safeDirectories(Path p) throws IOException {Path current=p.getRoot();for(Path part:p){current=current.resolve(part);requireNotLink(current);if(Files.exists(current,NOFOLLOW_LINKS)){if(!Files.isDirectory(current,NOFOLLOW_LINKS))throw new IOException("Store path is not a directory");}else Files.createDirectory(current);}}
    private static void requireNotLink(Path p)throws IOException{if(Files.isSymbolicLink(p))throw new IOException("Symbolic link in store path");}
    private static String readRecord(Path p)throws IOException{requireNotLink(p);if(!Files.exists(p,NOFOLLOW_LINKS))return null;if(!Files.isRegularFile(p,NOFOLLOW_LINKS)||Files.size(p)!=64)throw new IOException("Invalid install record");String s=Files.readString(p);if(!s.matches("[0-9a-f]{64}"))throw new IOException("Invalid install record");return s;}
    private static void writeRecord(Path p,String digest)throws IOException{String existing=readRecord(p);if(existing!=null){if(!existing.equals(digest))throw new IOException("Install record conflict");return;}Path temp=Files.createTempFile(p.getParent(),"record-",".tmp");try{try(FileChannel c=FileChannel.open(temp,WRITE,TRUNCATE_EXISTING)){ByteBuffer buffer=ByteBuffer.wrap(digest.getBytes(StandardCharsets.US_ASCII));while(buffer.hasRemaining())c.write(buffer);c.force(true);}Files.move(temp,p,StandardCopyOption.ATOMIC_MOVE);forceDirectory(p.getParent());}finally{Files.deleteIfExists(temp);}}
    private static void removeWork(Path path)throws IOException {
        if(!Files.exists(path,NOFOLLOW_LINKS)) return;
        try(var stream=Files.walk(path)) { for(Path p:stream.toList()) if(Files.isDirectory(p,NOFOLLOW_LINKS) && Files.getFileStore(p).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(p,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE)); }
        try(var stream=Files.walk(path)) { for(Path p:stream.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
    }
    private static String decode(byte[] bytes)throws IOException{try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException e){throw new IOException("Invalid UTF-8",e);}}
    private static int u16(byte[] b,int p)throws IOException{if(p<0 || p+2>b.length)throw new IOException("Truncated ZIP");return ByteBuffer.wrap(b,p,2).order(ByteOrder.LITTLE_ENDIAN).getShort()&65535;}
    private static long u32(byte[] b,int p)throws IOException{if(p<0 || p+4>b.length)throw new IOException("Truncated ZIP");return ByteBuffer.wrap(b,p,4).order(ByteOrder.LITTLE_ENDIAN).getInt()&0xffffffffL;}
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String hash(byte[] bytes){return HexFormat.of().formatHex(sha().digest(bytes));}
}
