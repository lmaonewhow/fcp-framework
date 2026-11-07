package fcp.control;

import fcp.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static java.nio.file.StandardOpenOption.*;

/** Development-only single-instance atomic file implementation. Production uses JDBC CAS. */
public final class FileStateJournal implements StateJournal {
    private static final Set<Path> OPEN=ConcurrentHashMap.newKeySet();
    private final Path directory,file;
    private final FileChannel channel;
    private final FileLock lock;
    private Snapshot current;
    private boolean closed,poisoned;
    public FileStateJournal(Path dir)throws IOException {
        Path absolute=dir.toAbsolutePath().normalize(),part=absolute.getRoot();
        for(Path segment:absolute){part=part.resolve(segment);try{Files.createDirectory(part);}catch(FileAlreadyExistsException ignored){}if(Files.isSymbolicLink(part)||!Files.isDirectory(part,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe journal ancestor");}
        directory=absolute.toRealPath();file=directory.resolve("lifecycle.json");
        if(!OPEN.add(directory))throw new IOException("Coordinator already open");FileChannel c=null;FileLock l=null;
        try{Path lp=directory.resolve("lifecycle.lock");if(Files.isSymbolicLink(lp)||Files.isSymbolicLink(file))throw new IOException("Journal symlink");c=FileChannel.open(lp,CREATE,WRITE,LinkOption.NOFOLLOW_LINKS);l=c.tryLock();if(l==null)throw new IOException("Coordinator already locked");
            if(Files.exists(file)){if(Files.size(file)>8*1024*1024||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Journal size/type");Map<String,Object> envelope=Schema.map(Json.parse(Files.readString(file)));if(!envelope.keySet().equals(Set.of("revision","json")))throw new IOException("Journal envelope schema");current=new Snapshot(Schema.number(envelope.get("revision")),Schema.str(envelope.get("json")));}else current=new Snapshot(0,null);
        }catch(IOException|RuntimeException e){if(l!=null)l.release();if(c!=null)c.close();OPEN.remove(directory);throw e;}
        channel=c;lock=l;
    }
    public synchronized Snapshot load()throws IOException{live();return current;}
    public synchronized Snapshot compareAndSet(long expected,String json)throws IOException{
        live();if(current.revision()!=expected)throw new IOException("JOURNAL_REVISION_CONFLICT");Snapshot next=new Snapshot(expected+1,json);byte[] bytes=Json.write(Map.of("revision",next.revision(),"json",json)).getBytes(java.nio.charset.StandardCharsets.UTF_8);if(bytes.length>8*1024*1024)throw new IOException("Journal limit");Path tmp=null;
        try{if(Files.isSymbolicLink(file))throw new IOException("Journal symlink");tmp=Files.createTempFile(directory,"journal-",".tmp");try(FileChannel c=FileChannel.open(tmp,WRITE)){ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())c.write(b);c.force(true);}Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);try(FileChannel c=FileChannel.open(directory,READ)){c.force(true);}current=next;return next;}
        catch(IOException e){poisoned=true;throw e;}finally{if(tmp!=null)Files.deleteIfExists(tmp);}
    }
    private void live()throws IOException{if(closed||poisoned)throw new IOException("Journal requires recovery");}
    public synchronized void close()throws IOException{if(closed)return;closed=true;try{lock.release();}finally{channel.close();OPEN.remove(directory);}}
}
