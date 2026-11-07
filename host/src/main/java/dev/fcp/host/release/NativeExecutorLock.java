package dev.fcp.host.release;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Linux single-owner guard. All instances using this runtime data MUST share this exact lock path. */
public final class NativeExecutorLock implements AutoCloseable {
    private final FileChannel channel;private final FileLock lock;
    public NativeExecutorLock(Path path)throws IOException {
        if(!path.isAbsolute()||!path.normalize().equals(path))throw new IOException("EXECUTOR_LOCK_ABSOLUTE_PATH_REQUIRED");
        Path part=path.getRoot();for(Path segment:path){part=part.resolve(segment);if(Files.isSymbolicLink(part))throw new IOException("EXECUTOR_LOCK_SYMLINK");}
        Path parent=path.getParent();if(!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS))throw new IOException("EXECUTOR_LOCK_DIRECTORY_REQUIRED");
        UserPrincipal owner=FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        check(parent,owner);if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("EXECUTOR_LOCK_NOT_REGULAR");check(path,owner);}
        channel=FileChannel.open(path,Set.of(StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try{check(path,owner);FileLock acquired;try{acquired=channel.tryLock();}catch(OverlappingFileLockException conflict){throw new IOException("EXECUTOR_ALREADY_OWNED",conflict);}if(acquired==null)throw new IOException("EXECUTOR_ALREADY_OWNED");lock=acquired;}
        catch(IOException|RuntimeException failure){channel.close();throw failure;}
    }
    private static void check(Path path,UserPrincipal owner)throws IOException {
        if(!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(owner))throw new IOException("EXECUTOR_LOCK_OWNER");
        for(PosixFilePermission p:Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS))if(p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_"))throw new IOException("EXECUTOR_LOCK_PRIVATE_DIRECTORY_REQUIRED");
    }
    public void close()throws IOException {try{if(lock.isValid())lock.release();}finally{channel.close();}}
}
