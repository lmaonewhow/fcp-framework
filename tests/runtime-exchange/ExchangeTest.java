import fcp.*;import fcp.host.*;import fcp.control.*;import fcp.plugin.*;import fcp.runtime.*;import fcp.storage.*;import fcp.exchange.*;
import java.io.*;import java.nio.file.*;import java.time.*;import java.util.*;import java.util.zip.*;import com.sun.net.httpserver.HttpServer;import java.net.*;
/** Actual package install and Python protocol, real file journal and localhost HTTP.
 * Storage callback is explicitly a fixture; native JDBC coverage belongs in native acceptance. */
public class ExchangeTest {
 static int checks=0;static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 interface Action{void run()throws Exception;}static void denied(String code,Action a)throws Exception{try{a.run();throw new AssertionError("Expected "+code);}catch(FcpException e){check(e.code.equals(code));}}
 static class Host implements HostCapabilities {boolean allow=true;long revision=1;public Descriptor descriptor(){return new Descriptor("test","1","fcp.host/1","embedded-single-instance",Map.of());}public Clock clock(){return Clock.systemUTC();}public Decision authorize(TrustedContext c,Grant g){return new Decision(allow&&(!g.dataScope().startsWith("class:")||c.dataScopes().contains(g.dataScope())),revision);}public void audit(Audit a){} }
 static TrustedContext ctx(boolean task,String tenant){return new TrustedContext(UUID.randomUUID().toString(),"trace",tenant,new TrustedContext.Principal(task?TrustedContext.Kind.TASK:TrustedContext.Kind.USER,task?"roster-sync":"teacher"),1,Set.of("class:a"),Instant.now().plusSeconds(120));}
 static InputStream csv(String s){return new ByteArrayInputStream(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 static String header="student_code,full_name,class_code\n";
 static PackageInstaller.InstalledPackage install(VerifiedPackageRepository repo,Path root,String plugin)throws Exception{Path zip=root.resolve(plugin+".zip");try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip))){for(String name:List.of("plugin.json","worker.py")){out.putNextEntry(new ZipEntry(name));out.write(Files.readAllBytes(Path.of("runtime/runtime-plugins",plugin,name)));out.closeEntry();}}return repo.install(zip,VerifiedPackageRepository.hash(Files.readAllBytes(zip)),"install-"+plugin);}
 public static void main(String[] args)throws Exception{
 Path root=Files.createTempDirectory("exchange-");Host host=new Host();var repo=new VerifiedPackageRepository(root.resolve("packages"),Set.of("fcp-fixtures"));Map<String,Object> records=new HashMap<>();
 var requests=new java.util.concurrent.atomic.AtomicInteger();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/campus",x->{requests.incrementAndGet();String cursor=x.getRequestURI().getQuery();boolean second=cursor.endsWith("page2");byte[] data=Json.write(Json.obj("content",header+(second?"t2,Task Two,a\n":"t1,Task One,a\n"),"nextCursor",second?null:"page2")).getBytes();x.sendResponseHeaders(200,data.length);x.getResponseBody().write(data);x.close();});server.start();
 try(var runtime=new EngineRuntimePort(host,repo,Map.of("python",args[0]),root.resolve("calls"),5);var lifecycle=new LifecycleCoordinator(root.resolve("lifecycle"),host,runtime)){
 lifecycle.setEnablementPolicy((caller,manifest,digest,closure)->"explicit-test-fixture-approval"); // Component fixture only.
 runtime.setStorageCompatibilityVerifier((a,b,c,d)->{}); // Fixture only; never a production migration bypass.
 for(var cap:StorageCapabilities.contracts())runtime.registerHostCapability(cap,(plugin,c,operation,input)->{String id=c.tenantId()+":"+plugin+":"+input.get("dataScope");if(operation.endsWith("get"))return records.get(id);if(!operation.endsWith("put"))throw new AssertionError();var old=records.get(id);long rev=old==null?0:Schema.number(Schema.map(old).get("revision"));if(rev!=Schema.number(input.get("expectedRevision")))throw new FcpException("VERSION_CONFLICT","fixture CAS");var result=Map.of("id","snapshot","revision",rev+1,"body",input.get("body"),"deleted",false);records.put(id,result);return result;});
 for(String p:List.of("roster","mock-exam")){var pkg=install(repo,root,p);lifecycle.register(ctx(false,"tenant"),pkg,lifecycle.revision());var plan=lifecycle.plan(ctx(false,"tenant"),"tenant",p,LifecycleCoordinator.Action.ENABLE,lifecycle.revision());lifecycle.confirm(ctx(false,"tenant"),plan,Set.of(p),"enable-"+p);}
 var port=new ExchangeService.ExecutionPort(){public String version(TrustedContext c,String p){var enabled=runtime.enabledVersion(c,p);return enabled.version()+":"+enabled.digest();}public Object call(TrustedContext c,String p,String op,Map<String,Object> in,String key){return runtime.invoke(c,p,op,in,key,null);}};
 var roster=new ExchangeService.Config("roster","roster","class:a","campus-v1-csv","fixture-1","1",Set.of("student_code","full_name","class_code"),"campus","roster-sync");var exam=new ExchangeService.Config("exam","mock-exam","class:a","campus-v1-csv","fixture-1","1",Set.of("exam_code","title","class_code"),"campus","exam-sync");var configs=List.of(roster,exam);Map<String,ExchangeService.Connector> connectors=Map.of("campus",new LoopbackConnector(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/campus")));
 try(var service=new ExchangeService(host,port,new FileStateJournal(root.resolve("exchange")),configs,connectors)){
 var c=ctx(false,"tenant");
 var apiPreview=service.previewConnector(c,"roster","");check("page2".equals(apiPreview.get("nextCursor")));check(Boolean.TRUE.equals(Schema.map(apiPreview.get("result")).get("valid")));
 host.allow=false;denied("PERMISSION_DENIED",()->service.previewConnector(c,"roster",""));host.allow=true;
 denied("USER_REQUIRED",()->service.previewConnector(ctx(true,"tenant"),"roster",""));
 var p=service.preview(c,"roster","uploaded.csv",csv(header+"s1,Alice,a\n"));check(Boolean.TRUE.equals(Schema.map(p.get("result")).get("valid")));check(records.isEmpty());
 var receipt=service.confirm(c,(String)p.get("previewId"),(String)p.get("digest"),"manual1");check(Schema.map(receipt).get("committed").equals(true));check(receipt.equals(service.confirm(c,(String)p.get("previewId"),(String)p.get("digest"),"manual1")));
 var duplicate=service.preview(c,"roster","bad.csv",csv(header+"s1,Alice,a\ns2,Bob,b\ns2,Bob,a\n"));check(Schema.list(Schema.map(duplicate.get("result")).get("errors")).size()==3);denied("VALIDATION_FAILED",()->service.confirm(c,(String)duplicate.get("previewId"),(String)duplicate.get("digest"),"bad"));
 var raced=service.preview(c,"roster","raced.csv",csv(header+"s4,Four,a\n"));
 var winner=service.preview(c,"roster","winner.csv",csv(header+"s5,Five,a\n"));service.confirm(c,(String)winner.get("previewId"),(String)winner.get("digest"),"winner");denied("CONFLICT",()->service.confirm(c,(String)raced.get("previewId"),(String)raced.get("digest"),"raced"));
 var stale=service.preview(c,"roster","next.csv",csv(header+"s3,Three,a\n"));host.revision++;denied("STALE_PREVIEW",()->service.confirm(c,(String)stale.get("previewId"),(String)stale.get("digest"),"stale"));
 denied("TASK_IDENTITY_REQUIRED",()->service.runTask(c,"roster"));host.allow=false;denied("PERMISSION_DENIED",()->service.runTask(ctx(true,"tenant"),"roster"));host.allow=true;
 check(Boolean.TRUE.equals(Schema.map(service.runTask(ctx(true,"tenant"),"roster")).get("committed")));
 int beforeDisable=requests.get();var disable=lifecycle.plan(c,"tenant","roster",LifecycleCoordinator.Action.DISABLE,lifecycle.revision());lifecycle.confirm(c,disable,Set.of("roster"),"pause-task");denied("ADMISSION_CLOSED",()->service.runTask(ctx(true,"tenant"),"roster"));check(requests.get()==beforeDisable);
 var enable=lifecycle.plan(c,"tenant","roster",LifecycleCoordinator.Action.ENABLE,lifecycle.revision());lifecycle.confirm(c,enable,Set.of("roster"),"resume-task");

 var handle=service.export(c,"roster",Set.of("student_code"));String download=new String(service.download(c,(String)handle.get("handle")));check(download.contains("s1")&&!download.contains("Alice"));host.allow=false;denied("PERMISSION_DENIED",()->service.download(c,(String)handle.get("handle")));host.allow=true;denied("DOWNLOAD_DENIED",()->service.download(ctx(false,"other"),(String)handle.get("handle")));denied("FIELD_DENIED",()->service.export(c,"roster",Set.of("private")));
 var e=service.preview(c,"exam","exams.csv",csv("exam_code,title,class_code\ne1,Practice,a\n"));check(Boolean.TRUE.equals(Schema.map(service.confirm(c,(String)e.get("previewId"),(String)e.get("digest"),"exam1")).get("committed")));
 }
 try(var service=new ExchangeService(host,port,new FileStateJournal(root.resolve("exchange")),configs,connectors)){check(Boolean.TRUE.equals(Schema.map(service.runTask(ctx(true,"tenant"),"roster")).get("committed")));check(Boolean.TRUE.equals(Schema.map(service.runTask(ctx(true,"tenant"),"roster")).get("complete")));var handle=service.export(ctx(false,"tenant"),"roster",Set.of("student_code"));check(new String(service.download(ctx(false,"tenant"),(String)handle.get("handle"))).contains("t2"));
 var tick=new java.util.concurrent.CountDownLatch(1);var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
 try(var scheduler=new FixedTaskScheduler(service,(tenant,task)->{tick.countDown();return ctx(true,tenant);},List.of(new FixedTaskScheduler.Schedule("tenant","roster-sync","roster",Duration.ofSeconds(1))),(schedule,error)->failure.set(error))){check(tick.await(4,java.util.concurrent.TimeUnit.SECONDS));}check(failure.get()==null);
 var deniedTick=new java.util.concurrent.CountDownLatch(1);
 try(var scheduler=new FixedTaskScheduler(service,(tenant,task)->ctx(false,tenant),List.of(new FixedTaskScheduler.Schedule("tenant","roster-sync","roster",Duration.ofSeconds(1))),(schedule,error)->deniedTick.countDown())){check(deniedTick.await(4,java.util.concurrent.TimeUnit.SECONDS));}
 }
 }finally{server.stop(0);}
 check(Schema.number(ProcessProvider.metrics().get("activeProcesses"))==0);System.out.println("PASS "+checks+" exchange checks: real packages/Python/file/loopback API; fixture storage, NOT native JDBC acceptance");
 }
}
