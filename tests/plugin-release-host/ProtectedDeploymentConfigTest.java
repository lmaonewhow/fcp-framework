import dev.fcp.host.config.ProtectedDeploymentConfig;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;

public class ProtectedDeploymentConfigTest {
    static int checks;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    static void denied(Path path)throws Exception{try{ProtectedDeploymentConfig.read(path);throw new AssertionError("Unsafe configuration accepted");}catch(IOException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("fcp-protected-config-");Path file=root.resolve("approved.json");
        Files.writeString(file,"{\"format\":1}");Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
        check(ProtectedDeploymentConfig.read(file).equals("{\"format\":1}"),"private regular config accepted");
        denied(Path.of("relative.json"));denied(root);
        Path link=root.resolve("link.json");Files.createSymbolicLink(link,file);denied(link);
        Path directoryLink=root.resolve("linked-dir");Files.createSymbolicLink(directoryLink,root);denied(directoryLink.resolve("approved.json"));
        for(String permissions:new String[]{"rw-r-----","rw--w----","rw----r--","rw-----w-"}){Files.setPosixFilePermissions(file,PosixFilePermissions.fromString(permissions));denied(file);}
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
        Files.writeString(file,"a".repeat(65536));check(ProtectedDeploymentConfig.read(file).length()==65536,"inclusive 64KiB bound");
        Files.writeString(file,"a".repeat(65537));denied(file);
        Files.write(file,new byte[]{(byte)0xc3,(byte)0x28});denied(file);
        Files.delete(link);Files.delete(directoryLink);Files.delete(file);Files.delete(root);
        System.out.println("PASS "+checks+" protected deployment configuration checks; native NOT_RUN");
    }
}
