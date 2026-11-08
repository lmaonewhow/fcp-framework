package dev.fcp.host.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Bounded deployment-owned UTF-8 configuration, never HTTP-provided configuration. */
public final class ProtectedDeploymentConfig {
    public static final int MAX_BYTES=65536;
    private ProtectedDeploymentConfig(){}
    public static String read(Path supplied)throws IOException {
        if(!supplied.isAbsolute())throw new IOException("CONFIG_ABSOLUTE_PATH_REQUIRED");
        Path path=supplied.normalize();
        for(Path item=path;item!=null;item=item.getParent())if(Files.isSymbolicLink(item))throw new IOException("CONFIG_SYMLINK_DENIED");
        var before=attributes(path);validate(path,before);
        ByteBuffer bytes=ByteBuffer.allocate(MAX_BYTES+1);
        try(var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))){
            while(bytes.hasRemaining()){int count=channel.read(bytes);if(count<0)break;}
        }
        if(bytes.position()>MAX_BYTES)throw new IOException("CONFIG_SIZE_LIMIT");
        var after=attributes(path);validate(path,after);
        if(!Objects.equals(before.fileKey(),after.fileKey())||before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||bytes.position()!=after.size())throw new IOException("CONFIG_CHANGED_DURING_READ");
        bytes.flip();return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes).toString();
    }
    private static PosixFileAttributes attributes(Path path)throws IOException {
        try{return Files.readAttributes(path,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
        catch(UnsupportedOperationException unsupported){throw new IOException("CONFIG_OWNER_PROTECTION_UNAVAILABLE",unsupported);}
    }
    private static void validate(Path path,PosixFileAttributes attributes)throws IOException {
        if(!attributes.isRegularFile()||attributes.isSymbolicLink())throw new IOException("CONFIG_REGULAR_FILE_REQUIRED");
        if(attributes.size()>MAX_BYTES)throw new IOException("CONFIG_SIZE_LIMIT");
        for(var permission:attributes.permissions())if(permission.name().startsWith("GROUP_")||permission.name().startsWith("OTHERS_"))throw new IOException("CONFIG_OWNER_ONLY_REQUIRED");
        UserPrincipal owner=path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        if(!owner.equals(attributes.owner()))throw new IOException("CONFIG_CURRENT_OWNER_REQUIRED");
    }
}
