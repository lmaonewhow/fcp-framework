package fcp.runtime;

import fcp.plugin.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** Trusted host inventory, retaining original archives so PackageInstaller can reverify every byte.
 * Never construct InstalledPackage from HTTP input. Package archives/digests require trusted approval.
 */
public final class VerifiedPackageRepository {
    private final Path archives;
    private final PackageInstaller installer;
    private final Path versions;
    public VerifiedPackageRepository(Path root,Set<String> publishers)throws IOException {
        safeDirectories(root);archives=root.resolve("archives");safeDirectories(archives);
        installer=new PackageInstaller(root.resolve("installed"),publishers);versions=root.resolve("installed/versions");
    }
    public synchronized PackageInstaller.InstalledPackage install(Path source,String digest,String key)throws IOException {
        validDigest(digest);if(Files.size(source)>64L*1024*1024)throw new IOException("Archive size limit");
        Path target=archives.resolve(digest+".zip");
        if(Files.isSymbolicLink(target))throw new IOException("Archive symlink");
        if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
            Path tmp=Files.createTempFile(archives,"archive-",".tmp");
            try {
                try(InputStream in=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS);OutputStream out=Files.newOutputStream(tmp,WRITE)){byte[] b=new byte[8192];long size=0;int n;while((n=in.read(b))!=-1){if((size+=n)>64L*1024*1024)throw new IOException("Archive size limit");out.write(b,0,n);}}
                if(!hash(Files.readAllBytes(tmp)).equals(digest))throw new IOException("Archive digest mismatch");
                try(FileChannel channel=FileChannel.open(tmp,WRITE)){channel.force(true);}Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE);
                try(FileChannel channel=FileChannel.open(archives,READ)){channel.force(true);}
            }finally{Files.deleteIfExists(tmp);}
        }
        return installer.install(target,digest,key);
    }
    /** Verify and retain an immutable package only. Does not change the coordinator's selected version. */
    public synchronized PackageInstaller.InstalledPackage stageOnly(Path source,String digest,String key)throws IOException{return install(source,digest,key);}
    public record VerifiedVersion(String version,String digest) {}
    /** Bounded inventory from installer-owned version receipts; every returned archive is reverified. */
    public synchronized List<VerifiedVersion> verifiedVersions(String plugin)throws IOException {
        if(plugin==null||!plugin.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")||plugin.length()>128)throw new IllegalArgumentException("Plugin identifier required");
        Path directory=versions.resolve(plugin);if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return List.of();
        if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe version inventory");
        List<Path> files;try(var stream=Files.list(directory)){files=stream.limit(257).sorted().toList();}if(files.size()>256)throw new IOException("Version inventory limit");
        List<VerifiedVersion> result=new ArrayList<>();for(Path file:files){String name=file.getFileName().toString();if(!name.endsWith(".sha256")||Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>128)throw new IOException("Invalid version receipt");String version=name.substring(0,name.length()-7),digest=Files.readString(file).trim();var pkg=resolve(plugin,version,digest);result.add(new VerifiedVersion(pkg.manifest().version(),pkg.sha256()));}
        return List.copyOf(result);
    }
    public synchronized PackageInstaller.InstalledPackage resolve(String plugin,String version,String digest)throws IOException {
        validDigest(digest);Path archive=archives.resolve(digest+".zip");
        if(Files.isSymbolicLink(archive)||!Files.isRegularFile(archive,LinkOption.NOFOLLOW_LINKS))throw new IOException("Verified archive unavailable");
        var pkg=installer.install(archive,digest,"verify-"+digest);
        if(!pkg.manifest().pluginId().equals(plugin)||!pkg.manifest().version().equals(version))throw new IOException("Inventory identity mismatch");
        return pkg;
    }
    public void verify(PackageInstaller.InstalledPackage candidate)throws IOException {
        var actual=resolve(candidate.manifest().pluginId(),candidate.manifest().version(),candidate.sha256());
        if(!actual.directory().equals(candidate.directory().toAbsolutePath().normalize())||!actual.manifest().equals(candidate.manifest()))throw new IOException("Forged installed package record");
    }
    public static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static void validDigest(String digest){if(digest==null||!digest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("SHA256 required");}
    static void safeDirectories(Path path)throws IOException{Path p=path.toAbsolutePath().normalize().getRoot();for(Path part:path.toAbsolutePath().normalize()){p=p.resolve(part);try{Files.createDirectory(p);}catch(FileAlreadyExistsException ignored){}if(!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(p))throw new IOException("Unsafe directory ancestor");}}
}
