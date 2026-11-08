import dev.fcp.host.release.NativeExecutorLock;
import java.nio.file.*;import java.nio.file.attribute.*;import java.io.*;
public class NativeExecutorLockTest {
 public static void main(String[] args)throws Exception {
  if(args.length==1){try(var owner=new NativeExecutorLock(Path.of(args[0]))){System.exit(3);}catch(IOException e){if(!e.getMessage().equals("EXECUTOR_ALREADY_OWNED"))throw e;System.out.println("CONTENDED");return;}}
  Path root=Files.createTempDirectory("fcp-executor-owner");Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));Path path=root.resolve("owner.lock");
  try(var owner=new NativeExecutorLock(path)) {
   var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),NativeExecutorLockTest.class.getName(),path.toString()).redirectErrorStream(true).start();
   String result=new String(child.getInputStream().readAllBytes());if(child.waitFor()!=0||!result.contains("CONTENDED"))throw new AssertionError("Second JVM acquired live executor lock: "+result);
  }
  try(var owner=new NativeExecutorLock(path)){}
  Path symlink=root.resolve("linked.lock");Files.createSymbolicLink(symlink,path);try(var ignored=new NativeExecutorLock(symlink)){throw new AssertionError("symlink accepted");}catch(IOException expected){if(!expected.getMessage().equals("EXECUTOR_LOCK_SYMLINK"))throw expected;}
  Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-r--r--"));try(var ignored=new NativeExecutorLock(path)){throw new AssertionError("public lock accepted");}catch(IOException expected){if(!expected.getMessage().equals("EXECUTOR_LOCK_PRIVATE_DIRECTORY_REQUIRED"))throw expected;}
  System.out.println("PASS native executor exclusive owner: real second JVM, release/reacquire, symlink/private-permission refusal");
 }
}
