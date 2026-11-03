import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.*;
import java.util.*;

/** Generates fresh native protocol keys. Never prints private material. Run with a JDK 17+ source launcher. */
public class GenerateKeys {
    public static void main(String[] args) throws Exception {
        if (args.length!=1) throw new IllegalArgumentException("Usage: java bootstrap/GenerateKeys.java <private-output-directory>");
        Path dir=Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(dir);
        Path build=dir.resolve("build.env"),runtime=dir.resolve("fcp-runtime.properties");
        if (Files.exists(build)||Files.exists(runtime)) throw new IllegalStateException("Refusing to overwrite existing key files");
        boolean posix=Files.getFileStore(dir).supportsFileAttributeView("posix");
        if (posix) Files.setPosixFilePermissions(dir,EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE));
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
        KeyPair request=generator.generateKeyPair(),response=generator.generateKeyPair();
        String client="FCP_CLIENT_ID=e5cd7e4891bf95d1d19206ce24a7b32e\n"+
            "FCP_REQUEST_PUBLIC_KEY="+b64(request.getPublic().getEncoded())+"\n"+
            "FCP_RESPONSE_PRIVATE_KEY="+b64(response.getPrivate().getEncoded())+"\n";
        byte[] jwt=new byte[48],monitor=new byte[32];new SecureRandom().nextBytes(jwt);new SecureRandom().nextBytes(monitor);
        String server="# Keep outside version control. Change database/Redis placeholders before startup.\n"+
            "FCP_JDBC_URL=jdbc:mysql://127.0.0.1:3306/fcp_acceptance?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&sslMode=REQUIRED\n"+
            "FCP_DB_USER=fcp_runtime\nFCP_DB_PASSWORD=REPLACE_WITH_DATABASE_PASSWORD\nFCP_REDIS_PASSWORD=REPLACE_WITH_REDIS_PASSWORD\n"+
            "FCP_REDIS_HOST=127.0.0.1\nFCP_REDIS_PORT=6379\nFCP_REDIS_PREFIX=fcp_local_"+UUID.randomUUID().toString().replace("-", "").substring(0,16)+"\n"+
            "FCP_JWT_SECRET="+HexFormat.of().formatHex(jwt)+"\nFCP_MONITOR_PASSWORD="+HexFormat.of().formatHex(monitor)+"\n"+
            "FCP_REQUEST_PRIVATE_KEY="+b64(request.getPrivate().getEncoded())+"\n"+
            "FCP_RESPONSE_PUBLIC_KEY="+b64(response.getPublic().getEncoded())+"\n";
        write(build,client,posix);write(runtime,server,posix);
        System.out.println("Created owner-only key/config files in "+dir+"; RSA 2048. Browser response-decryption key is intentionally client-visible.");
    }
    static String b64(byte[] data){return Base64.getEncoder().encodeToString(data);}
    static void write(Path p,String value,boolean posix) throws Exception {
        if(posix) Files.createFile(p,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE)));
        else Files.createFile(p);
        Files.writeString(p,value,StandardOpenOption.WRITE);
    }
}
