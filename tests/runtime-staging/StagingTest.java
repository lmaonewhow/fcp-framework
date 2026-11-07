import dev.fcp.host.*;
import fcp.*;import fcp.host.*;import fcp.control.*;import fcp.runtime.*;import fcp.storage.*;
import java.nio.file.*;import java.util.*;import java.time.*;import java.io.*;import java.util.zip.*;
/** Actual ZIP verification/journal/Engine; permission decisions explicitly supplied by fixture. */
public class StagingTest {
 static class Host implements HostCapabilities {
  boolean deny;
  public Descriptor descriptor(){return new Descriptor("fixture","1","fcp.host/1","embedded-single-instance",Map.of());}
  public Clock clock(){return Clock.systemUTC();}
  public Decision authorize(TrustedContext c,Grant g){return new Decision(!deny,1);}
  public void audit(Audit a){}
 }
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args)throws Exception {
  Path root=Files.createTempDirectory("fcp-stage-");Host host=new Host();var context=new TrustedContext("stage","trace","000000",new TrustedContext.Principal(TrustedContext.Kind.USER,"fixture"),1,Set.of(),Instant.now().plusSeconds(60));
  Map<String,Object> storage=Map.of("schemaVersion",1,"compatibleVersions",List.of(1),"tables",List.of(Map.of("name","records","fields",List.of(Map.of("name","name","type","string","nullable",true,"maxLength",100)),"indexes",List.of())),"migrations",List.of());
  var manifest=Json.obj("manifestVersion",1,"pluginId","stage.test","version","1.0.0","publisher","test","contractVersion","1.0","runtime",Map.of("type","local","entry","main.py","argv",List.of()),"provides",List.of(),"storage",storage);
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry("plugin.json"));zip.write(Json.write(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();zip.putNextEntry(new ZipEntry("main.py"));zip.write("raise RuntimeError('staging must not execute')".getBytes());zip.closeEntry();}
  byte[] archive=bytes.toByteArray();String digest=VerifiedPackageRepository.hash(archive);
  try {
   var repo=new VerifiedPackageRepository(root.resolve("packages"),Set.of("test"));
   try(var runtime=new EngineRuntimePort(host,repo,Map.of(),root.resolve("calls"),3);var lifecycle=new LifecycleCoordinator(root.resolve("control"),host,runtime)){
    var app=new PluginApplication(host,repo,lifecycle,runtime,root.resolve("uploads"),c->Set.of());
    var stage=app.stage(context,new ByteArrayInputStream(archive),digest,"same-key");check(stage.get("state").equals("VERIFIED"));check(Boolean.FALSE.equals(stage.get("replayed")));
    check(Boolean.TRUE.equals(app.stage(context,new ByteArrayInputStream(archive),digest,"same-key").get("replayed")));
    check(lifecycle.revision()==0&&lifecycle.installedMetadata(context,"stage.test").get("state").equals("ABSENT"));check(runtime.snapshot(context).enabledManifests().isEmpty());
    var resolved=repo.resolve("stage.test","1.0.0",digest);check(StorageDeclaration.parse(resolved.manifest().storage()).version()==1); // Same verified digest/declaration consumed by native storage planning.
    try{app.install(context,new ByteArrayInputStream(archive),digest,"install",0);throw new AssertionError("Missing schema allowed install");}catch(FcpException e){check(e.code.equals("STORAGE_ADAPTER_REQUIRED"));}
    check(lifecycle.installedMetadata(context,"stage.test").get("state").equals("ABSENT"));
    host.deny=true;try{app.stage(context,new ByteArrayInputStream(archive),digest,"same-key");throw new AssertionError("Revoked replay allowed");}catch(FcpException e){check(e.code.equals("PERMISSION_DENIED"));}
    check(Schema.number(ProcessProvider.metrics().get("activeProcesses"))==0);
   }
  } finally {try(var dirs=Files.walk(root)){dirs.filter(Files::isDirectory).forEach(p->p.toFile().setWritable(true));}try(var files=Files.walk(root)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
  System.out.println("PASS verified schema-bearing ZIP staged/resolved without registration/execution; original install schema gate and live replay authorization preserved. MySQL deployment NOT_RUN.");
 }
}
