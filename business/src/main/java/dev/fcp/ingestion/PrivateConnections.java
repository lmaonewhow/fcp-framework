package dev.fcp.ingestion;

import fcp.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.function.BiFunction;
import static fcp.Schema.*;

/** Operator-owned connection secrets. Never accepts a path, URL or credential from a caller. */
public final class PrivateConnections implements BiFunction<String,String,Map<String,Object>> {
    private final Path file;
    public PrivateConnections(Path file){this.file=file==null?null:file.toAbsolutePath().normalize();}
    @Override public Map<String,Object> apply(String scope,String plugin){
        if(file==null)throw new FcpException("INVALID_INPUT","Configure FCP_INGESTION_CONNECTIONS before using an API connector");
        try {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().equals(file)||Files.size(file)>65536)
                throw new IllegalArgumentException();
            if(Files.getFileStore(file).supportsFileAttributeView("posix")) {
                for(Path path:List.of(file,file.getParent())) {
                    Set<PosixFilePermission> permissions=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);
                    if(permissions.stream().anyMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")))throw new IllegalArgumentException();
                    if(!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).getName().equals(System.getProperty("user.name")))throw new IllegalArgumentException();
                }
            }
            Map<String,Object> config=map(Json.parse(Files.readString(file)));
            if(!config.keySet().equals(Set.of("format","tenants"))||number(config.get("format"))!=1)throw new IllegalArgumentException();
            Map<String,Object> tenant=obj(obj(config,"tenants"),scope),connection=obj(tenant,plugin);
            if(connection.isEmpty())throw new IllegalArgumentException();
            return map(cloneJson(connection));
        }catch(Exception failure){
            // Parsing errors can contain secret material. Do not attach the cause or path.
            throw new FcpException("INVALID_INPUT","Private connector configuration is missing, unsafe or invalid for this tenant");
        }
    }
}
