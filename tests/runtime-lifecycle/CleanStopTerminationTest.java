import fcp.*;import fcp.runtime.*;import java.io.*;import java.nio.file.*;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;import java.util.zip.*;import java.util.stream.Stream;
/** Actual live child with explicit termination-denial and wait-timeout doubles. */
public class CleanStopTerminationTest {
 static class Unkillable extends Process {
  final Process actual;final ProcessHandle handle;
  Unkillable(Process actual){this.actual=actual;var original=actual.toHandle();handle=(ProcessHandle)Proxy.newProxyInstance(ProcessHandle.class.getClassLoader(),new Class<?>[]{ProcessHandle.class},(p,m,a)->{
   if(m.getName().equals("destroy")||m.getName().equals("destroyForcibly"))return false;
   if(m.getName().equals("descendants"))return Stream.empty();
   try{return m.invoke(original,a);}catch(InvocationTargetException e){throw e.getCause();}
  });}
  public OutputStream getOutputStream(){return actual.getOutputStream();}public InputStream getInputStream(){return actual.getInputStream();}public InputStream getErrorStream(){return actual.getErrorStream();}
  public int waitFor()throws InterruptedException{return actual.waitFor();}public boolean waitFor(long t,TimeUnit u){return false;}
  public int exitValue(){return actual.exitValue();}public void destroy(){}public Process destroyForcibly(){return this;}public boolean isAlive(){return actual.isAlive();}public ProcessHandle toHandle(){return handle;}
 }
 public static void main(String[] args)throws Exception {
  Path root=Files.createTempDirectory("fcp-stop-proof-");var host=new RealRuntimeTest.Host();var repo=new VerifiedPackageRepository(root.resolve("packages"),Set.of("test"));
  var manifest=Json.obj("manifestVersion",1,"pluginId","proof","version","1.0.0","publisher","test","contractVersion","1.0","runtime",Json.obj("type","local","entry","runtime.py","argv",List.of()),"provides",List.of(RealRuntimeTest.cap("echo",false)));
  Path archive=root.resolve("proof.zip");try(var out=new ZipOutputStream(Files.newOutputStream(archive))){out.putNextEntry(new ZipEntry("plugin.json"));out.write(Json.write(manifest).getBytes());out.closeEntry();out.putNextEntry(new ZipEntry("runtime.py"));out.write(Files.readString(Path.of("tests/runtime-lifecycle/plugins/runtime.py")).replace("if m=='shutdown':break","# fixture acknowledges shutdown but stays alive").getBytes());out.closeEntry();}
  var pkg=repo.install(archive,VerifiedPackageRepository.hash(Files.readAllBytes(archive)),"proof");Process actual=null;Path journal=root.resolve("calls");
  try(var runtime=new EngineRuntimePort(host,repo,Map.of("python",args[0]),journal,1)){
   runtime.prepare("proof","1.0.0",pkg.sha256());
   Field runners=EngineRuntimePort.class.getDeclaredField("runners");runners.setAccessible(true);Object runner=((Map<?,?>)runners.get(runtime)).get("proof");Field transport=runner.getClass().getDeclaredField("transport");transport.setAccessible(true);var provider=(ProcessProvider)transport.get(runner);
   Field process=ProcessProvider.class.getDeclaredField("process");process.setAccessible(true);actual=(Process)process.get(provider);process.set(provider,new Unkillable(actual));
   RealRuntimeTest.rejected(()->runtime.stop("proof"),"RUNNER_TERMINATION_UNKNOWN");
   RealRuntimeTest.check(provider.identity()==null,"transport reference cleared");RealRuntimeTest.check(actual.isAlive(),"original OS child still alive despite cleared reference");
   RealRuntimeTest.rejected(()->runtime.prepare("proof","1.0.0",pkg.sha256()),"RESTART_BUDGET");
  } finally {if(actual!=null){actual.destroyForcibly();actual.waitFor(5,TimeUnit.SECONDS);}}
  try(var runtime=new EngineRuntimePort(host,repo,Map.of("python",args[0]),journal,1)){RealRuntimeTest.rejected(()->runtime.prepare("proof","1.0.0",pkg.sha256()),"RESTART_BUDGET");}
  System.out.println("PASS actual live Python + explicit failed-termination double: cleared transport reference is not exit proof, UNKNOWN fails closed, budget remains charged across reopen");
 }
}
