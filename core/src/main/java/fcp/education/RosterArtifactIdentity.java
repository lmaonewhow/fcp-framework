package fcp.education;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Bind the roster grant identity to this supported executable-JAR launch, not an environment
 * assertion. This is not artifact signing, hostile-host attestation or a dynamic-code sandbox.
 * The deployment owner must keep the archive immutable and control agents/class loaders.
 */
public final class RosterArtifactIdentity {
    private static final String FAILURE="ROSTER_EXECUTION_ARTIFACT_MISMATCH";
    private RosterArtifactIdentity() {}
    private static IllegalStateException failure(){return new IllegalStateException(FAILURE);}
    public static String verifyCurrentArchive(String configuredDigest,Class<?> anchor) {
        try {
            String[] arguments=ProcessHandle.current().info().arguments().orElseThrow(RosterArtifactIdentity::failure);
            String command=ProcessHandle.current().info().command().orElseThrow(RosterArtifactIdentity::failure);
            if(!Set.of("java","java.exe").contains(Path.of(command).getFileName().toString()))throw failure();
            Path archive=launchArchive(arguments);
            var protection=anchor.getProtectionDomain();
            if(protection==null||protection.getCodeSource()==null||protection.getCodeSource().getLocation()==null)throw failure();
            Path codeArchive=codeArchive(protection.getCodeSource().getLocation().toExternalForm());
            if(!Files.isSameFile(archive,codeArchive))throw failure();
            // Also bind the verifier itself, so an outside class path cannot stand in for this gate.
            var own=RosterArtifactIdentity.class.getProtectionDomain().getCodeSource();
            if(own==null||!Files.isSameFile(archive,codeArchive(own.getLocation().toExternalForm())))throw failure();
            return verifyDigest(archive,configuredDigest);
        } catch(Exception rejected) {throw failure();}
    }
    /** Only the explicitly supported VM prefix is accepted. The first launch selector must -jar.
     * No scanning through main-class/application arguments, @argfiles, class/module/source launches,
     * agents, alternative classpaths or unrecognized launcher options.
     */
    static Path launchArchive(String[] arguments) {
        if(arguments==null)throw failure();
        int i=0;
        while(i<arguments.length) {
            String argument=arguments[i];
            if(argument==null)throw failure();
            if(argument.matches("-Xm[sx][1-9][0-9]*[kKmMgG]?")){i++;continue;}
            if(argument.equals("-jar")) {
                if(i+1>=arguments.length)throw failure();
                return archivePath(arguments[i+1]);
            }
            throw failure();
        }
        throw failure();
    }
    private static Path archivePath(String value) {
        try {
            if(value==null||value.isBlank()||!value.endsWith(".jar")||value.indexOf('!')>=0)throw failure();
            Path path=Path.of(value).toAbsolutePath().normalize();
            if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!Files.isReadable(path))throw failure();
            return path.toRealPath();
        }catch(Exception rejected){throw failure();}
    }
    /** Standard executable jar, Spring Boot 3 nested classes, and Boot's prior jar:file form. */
    static Path codeArchive(String location) {
        try {
            String uri;
            if(location.startsWith("jar:nested:")&&location.endsWith("/!BOOT-INF/classes/!/")) {
                uri="file:"+location.substring("jar:nested:".length(),location.length()-"/!BOOT-INF/classes/!/".length());
            } else if(location.startsWith("jar:file:")&&location.endsWith("!/BOOT-INF/classes!/")) {
                uri=location.substring("jar:".length(),location.length()-"!/BOOT-INF/classes!/".length());
            } else if(location.startsWith("file:"))uri=location;
            else throw failure();
            URI parsed=URI.create(uri);if(parsed.getQuery()!=null||parsed.getFragment()!=null)throw failure();
            return archivePath(Path.of(parsed).toString());
        }catch(Exception rejected){throw failure();}
    }
    static String verifyDigest(Path archive,String expected) {
        try {
            if(expected==null||!expected.matches("[a-f0-9]{64}"))throw failure();
            Path path=archivePath(archive.toString());
            BasicFileAttributes before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream input=Files.newInputStream(path)){byte[] chunk=new byte[65536];for(int size;(size=input.read(chunk))>=0;)if(size>0)digest.update(chunk,0,size);}
            BasicFileAttributes after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!Objects.equals(before.fileKey(),after.fileKey()))throw failure();
            String actual=HexFormat.of().formatHex(digest.digest());
            if(!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw failure();
            return actual;
        }catch(Exception rejected){throw failure();}
    }
}
