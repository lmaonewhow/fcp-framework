package fcp.release.version;

import fcp.FcpException;
import fcp.Json;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;

/** Single-machine immutable version catalog. Does not download or execute artifacts. */
public final class FileVersionProvider implements VersionProvider {
    private static final int LIMIT=1024*1024;
    private static final Map<Path,Object> LOCKS=new ConcurrentHashMap<>();
    private final String id;
    private final Path root;
    public FileVersionProvider(String id,Path root) {
        VersionCodec.id(id);this.id=id;
        try {
            this.root=root.toAbsolutePath().normalize();
            Files.createDirectories(this.root);checkDirectory(this.root);
            for(String folder:List.of("artifacts","releases")){Path p=this.root.resolve(folder);Files.createDirectories(p);checkDirectory(p);}
        }catch(IOException e){throw failure("VERSION_STORAGE",e);}
    }
    public String id(){return id;}
    public Set<Capability> capabilities(){return Set.of(Capability.READ,Capability.REGISTER_IMMUTABLE);}
    public SystemArtifact registerArtifact(SystemArtifact a){return locked(()->{
        // A component/version label cannot silently point to different bytes under another id.
        for(SystemArtifact old:artifacts())if(old.component().equals(a.component())&&old.kind()==a.kind()&&old.version().equals(a.version())&&!old.equals(a))throw conflict();
        put("artifacts",a.id(),VersionCodec.artifact(a));return a;
    });}
    public SystemRelease registerRelease(SystemRelease r){return locked(()->{
        for(String a:r.components().values())if(artifact(a).isEmpty())throw new FcpException("ARTIFACT_NOT_FOUND","Release references an unknown artifact");
        for(SystemRelease old:releases())if(old.version().equals(r.version())&&!old.equals(r))throw conflict();
        put("releases",r.id(),VersionCodec.release(r));return r;
    });}
    public Optional<SystemArtifact> artifact(String id){return read("artifacts",id).map(VersionCodec::artifact);}
    public Optional<SystemRelease> release(String id){return read("releases",id).map(VersionCodec::release);}
    public List<SystemArtifact> artifacts(){return list("artifacts").stream().map(VersionCodec::artifact).toList();}
    public List<SystemRelease> releases(){return list("releases").stream().map(VersionCodec::release).toList();}
    /** Verify already-obtained bytes against metadata; trust/publisher verification is a separate gate. */
    public static void verifyBytes(SystemArtifact artifact,Path file) {
        try {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=artifact.sizeBytes())throw new FcpException("ARTIFACT_INTEGRITY","Artifact size mismatch");
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)digest.update(buf,0,n);}
            if(!HexFormat.of().formatHex(digest.digest()).equals(artifact.sha256()))throw new FcpException("ARTIFACT_INTEGRITY","Artifact digest mismatch");
        }catch(FcpException e){throw e;}catch(Exception e){throw failure("ARTIFACT_INTEGRITY",e);}
    }
    private <T>T locked(Supplier<T> body){
        synchronized(LOCKS.computeIfAbsent(root,x->new Object())){
            try{checkDirectory(root);try(FileChannel channel=FileChannel.open(root.resolve("catalog.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var lock=channel.lock()){return body.get();}}
            catch(IOException e){throw failure("VERSION_STORAGE",e);}
        }
    }
    private void put(String folder,String id,Map<String,Object> value){
        String payload=VersionCodec.canonical(value);
        Optional<Object> existing=read(folder,id);
        if(existing.isPresent()){if(!VersionCodec.canonical(existing.get()).equals(payload))throw conflict();return;}
        String document=VersionCodec.canonical(Map.of("format",1,"sha256",VersionCodec.sha256(payload),"value",value));
        byte[] bytes=document.getBytes(StandardCharsets.UTF_8);if(bytes.length>LIMIT)throw new IllegalArgumentException("Version document too large");
        Path dir=root.resolve(folder),tmp=null;
        try {
            checkDirectory(dir);tmp=Files.createTempFile(dir,".pending-",".tmp");
            try(FileChannel channel=FileChannel.open(tmp,StandardOpenOption.WRITE)){java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(bytes);while(b.hasRemaining())channel.write(b);channel.force(true);}
            Files.move(tmp,dir.resolve(id+".json"),StandardCopyOption.ATOMIC_MOVE);
            try(FileChannel directory=FileChannel.open(dir,StandardOpenOption.READ)){directory.force(true);}
        }catch(IOException e){throw failure("VERSION_STORAGE",e);}finally{if(tmp!=null)try{Files.deleteIfExists(tmp);}catch(IOException ignored){}}
    }
    private Optional<Object> read(String folder,String id){
        VersionCodec.id(id);Path dir=root.resolve(folder),path=dir.resolve(id+".json");
        try {
            checkDirectory(root);checkDirectory(dir);
            if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return Optional.empty();
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>LIMIT)throw new IOException("Invalid catalog entry");
            byte[] bytes;try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes(LIMIT+1);}if(bytes.length>LIMIT)throw new IOException("Catalog entry too large");
            Map<String,Object> envelope=VersionCodec.object(Json.parse(new String(bytes,StandardCharsets.UTF_8)),Set.of("format","sha256","value"));
            if(!new java.math.BigDecimal("1").equals(envelope.get("format"))||!VersionCodec.sha256(VersionCodec.canonical(envelope.get("value"))).equals(envelope.get("sha256")))throw new IOException("Catalog digest or format mismatch");
            Object decoded=folder.equals("artifacts")?VersionCodec.artifact(envelope.get("value")):VersionCodec.release(envelope.get("value"));
            String storedId=decoded instanceof SystemArtifact a?a.id():((SystemRelease)decoded).id();
            if(!storedId.equals(id))throw new IOException("Catalog identity mismatch");
            return Optional.of(envelope.get("value"));
        }catch(IOException|IllegalArgumentException e){throw failure("VERSION_CORRUPT",e);}
    }
    private List<Object> list(String folder){
        try{checkDirectory(root);checkDirectory(root.resolve(folder));try(var files=Files.list(root.resolve(folder))){List<Path> paths=files.filter(p->p.getFileName().toString().endsWith(".json")).sorted().toList();if(paths.size()>10000)throw new IOException("Catalog listing limit");List<Object> values=new ArrayList<>();for(Path path:paths){String n=path.getFileName().toString();values.add(read(folder,n.substring(0,n.length()-5)).orElseThrow());}return List.copyOf(values);}}
        catch(IOException e){throw failure("VERSION_STORAGE",e);}
    }
    private static void checkDirectory(Path path)throws IOException {for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Symlink catalog paths are forbidden");if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Not a catalog directory");}
    private static FcpException conflict(){return new FcpException("VERSION_CONFLICT","Immutable version already has different content");}
    private static FcpException failure(String code,Exception e){return new FcpException(code,"Version catalog operation failed",e);}
}
