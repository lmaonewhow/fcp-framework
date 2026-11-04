import dev.fcp.ingestion.IngestionService;
import dev.fcp.dual.*;
import dev.fcp.school.SchoolFacade.Identity;
import fcp.*;
import fcp.state.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static fcp.Schema.*;

/** Real Java -> Python/Node process, HTTP fixture and durable file-store regressions.
 * This test intentionally makes no Spring/MySQL/browser claim.
 */
public final class IngestionRegression {
    static int checks;
    static void check(String name,boolean condition){if(!condition)throw new AssertionError(name);checks++;System.out.println("CHECK "+name);}
    static void denied(String name,String code,Runnable action){try{action.run();throw new AssertionError(name+": unexpectedly allowed");}catch(FcpException error){check(name,code.equals(error.code));}}
    static Identity user(String scope,String subject,String... permissions){Set<String> grants=new HashSet<>();for(String p:permissions)grants.add("fcp:ingest:"+p);return new Identity(scope,subject,grants,Set.of("class-a"));}
    static Map<String,Object> status(Map<String,Object> catalog,String id){return arr(catalog,"plugins").stream().map(Schema::map).filter(p->id.equals(p.get("id"))).findFirst().orElseThrow();}
    static String envelope(Map<String,Object> catalog,Map<String,Object> input){return Json.write(Json.obj("input",input,"instance",catalog.get("instance"),"generation",catalog.get("generation"),"expectedCaller",catalog.get("caller")));}
    static void enable(IngestionService service,Identity who,String id,boolean enabled){Map<String,Object> c=service.describe(who);service.toggle(who,envelope(c,Json.obj("plugin",id,"enabled",enabled,"revision",status(c,id).get("revision"))));}
    static String request(IngestionService service,Identity who,String id,long version,String key,Map<String,Object> payload){return envelope(service.describe(who),Json.obj("plugin",id,"classId","class-a","expectedVersion",version,"requestKey",key,"payload",payload));}
    static Map<String,Object> csv(String text){return Json.obj("content",Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)),"encoding","utf-8-sig");}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]),data=Files.createTempDirectory("fcp-ingestion-regression-");String origin=args[1];
        FileRecordStore store=new FileRecordStore(data.resolve("records"));
        FilePluginStateStore states=new FilePluginStateStore(data.resolve("states"),1024);
        Identity admin=user("tenant-demo","1","view","import","manage"),viewer=user("tenant-demo","2","view"),other=user("tenant-other","3","view","import","manage");
        AtomicInteger rechecks=new AtomicInteger(),denyAt=new AtomicInteger(-1);
        Consumer<Identity> guard=i->{if(rechecks.incrementAndGet()==denyAt.get())throw new FcpException("FORBIDDEN","Revoked by host fixture");};
        java.util.concurrent.atomic.AtomicReference<String> mode=new java.util.concurrent.atomic.AtomicReference<>("demo");
        var config=(java.util.function.BiFunction<String,String,Map<String,Object>>)(scope,id)->Json.obj("baseUrl",origin,"token",mode.get(),"allowLoopback",true);
        IngestionService service=new IngestionService(root,store,states,guard,config);
        String firstRequest=null;long pid=0;
        try {
            var catalog=service.describe(admin);
            check("six installed plugins",arr(catalog,"plugins").size()==6);
            check("all initially disabled",arr(catalog,"plugins").stream().map(Schema::map).noneMatch(p->Boolean.TRUE.equals(p.get("enabled"))));
            denied("disabled business denies reads","PLUGIN_DISABLED",()->service.read(admin,"roster","class-a"));
            denied("viewer cannot manage plugins","FORBIDDEN",()->enable(service,viewer,"roster.business",true));
            enable(service,admin,"roster.connector",true);
            check("source waits for disabled business",!Boolean.TRUE.equals(status(service.describe(admin),"roster.connector").get("effective")));
            var stale=service.describe(admin);enable(service,admin,"roster.business",true);
            denied("stale catalog rejected","STALE_COMPOSITION",()->service.toggle(admin,envelope(stale,Json.obj("plugin","roster.connector","enabled",false,"revision",status(stale,"roster.connector").get("revision")))));
            check("business activation restores source",Boolean.TRUE.equals(status(service.describe(admin),"roster.connector").get("effective")));
            var now=service.describe(admin);
            denied("stale state revision rejected","STALE_COMPOSITION",()->service.toggle(admin,envelope(now,Json.obj("plugin","roster.connector","enabled",false,"revision",1))));
            firstRequest=request(service,admin,"roster.connector",0,"roster-api-0001",Map.of());
            var receipt=service.ingest(admin,firstRequest);
            check("paginated API roster imported",number(receipt.get("inserted"))==3);
            check("real roster Python output",number(obj(service.read(admin,"roster","class-a"),"summary").get("students"))==3);
            pid=number(service.read(admin,"roster","class-a").get("pid"));check("Python child actually alive",ProcessHandle.of(pid).orElseThrow().isAlive());
            check("same request replays",Boolean.TRUE.equals(service.ingest(admin,firstRequest).get("replayed")));
            check("replay creates no duplicate audit",store.history(admin.scope(),"ingest-roster","class-a").size()==1);
            denied("idempotency key payload conflict","IDEMPOTENCY_CONFLICT",()->service.ingest(admin,request(service,admin,"roster.connector",1,"roster-api-0001",Map.of())));
            denied("optimistic dataset version enforced","RECORD_CONFLICT",()->service.ingest(admin,request(service,admin,"roster.connector",0,"roster-api-stale",Map.of())));
            denied("caller cannot supply endpoint","INVALID_INPUT",()->service.ingest(admin,request(service,admin,"roster.connector",1,"roster-url-0001",Json.obj("baseUrl","http://169.254.169.254"))));
            denied("viewer cannot import","FORBIDDEN",()->service.ingest(viewer,request(service,viewer,"roster.connector",1,"viewer-00001",Map.of())));
            denied("unassigned class rejected","FORBIDDEN",()->service.read(viewer,"roster","class-b"));
            enable(service,admin,"roster.adapter",true);
            var rosterCsv=csv("学号,姓名,班级\n001,示例学生甲更新,class-a\n004,示例学生丁,class-a\n");
            receipt=service.ingest(admin,request(service,admin,"roster.adapter",1,"roster-csv-0001",rosterCsv));
            check("CSV upsert adds one",number(receipt.get("inserted"))==1);
            check("CSV upsert updates one",number(receipt.get("updated"))==1);
            check("leading-zero IDs and deduplication",arr(service.read(admin,"roster","class-a"),"records").size()==4);
            var invalidCsv=csv("学号,姓名,班级\n005,Valid,class-a\n006,Foreign,class-b\n");
            denied("foreign class batch rejected atomically","FORBIDDEN",()->service.ingest(admin,request(service,admin,"roster.adapter",2,"invalid-csv-001",invalidCsv)));
            check("failed batch leaves version unchanged",number(service.read(admin,"roster","class-a").get("version"))==2);
            enable(service,admin,"attendance.business",true);enable(service,admin,"attendance.connector",true);enable(service,admin,"attendance.adapter",true);
            receipt=service.ingest(admin,request(service,admin,"attendance.connector",0,"attendance-api-01",Map.of()));
            check("API attendance imported",number(receipt.get("inserted"))==3);
            check("Python attendance aggregation",obj(receipt,"summary").get("attendanceRate").toString().equals("66.67"));
            var attendanceCsv=csv("学号,班级,日期,状态\n003,class-a,2026-11-03,正常\n004,class-a,2026-11-03,请假\n");
            receipt=service.ingest(admin,request(service,admin,"attendance.adapter",1,"attendance-csv-01",attendanceCsv));
            check("CSV attendance update and insert",number(receipt.get("updated"))==1&&number(receipt.get("inserted"))==1);
            check("leave excluded from attendance denominator",number(obj(receipt,"summary").get("attendanceRate"))==100);
            var missing=csv("学号,班级,日期,状态\n999,class-a,2026-11-03,正常\n");
            denied("attendance requires same-class roster","BUSINESS_RULE",()->service.ingest(admin,request(service,admin,"attendance.adapter",2,"missing-student-01",missing)));
            check("referential failure leaves no write",number(service.read(admin,"attendance","class-a").get("version"))==2);
            mode.set("loop");denied("API pagination loop rejected","INVALID_INPUT",()->service.ingest(admin,request(service,admin,"roster.connector",2,"loop-api-0001",Map.of())));
            mode.set("redirect");denied("API redirect rejected","INVALID_INPUT",()->service.ingest(admin,request(service,admin,"roster.connector",2,"redirect-api-01",Map.of())));mode.set("demo");
            String revoke=request(service,admin,"roster.adapter",2,"revoke-import-01",rosterCsv);
            rechecks.set(0);denyAt.set(6);
            denied("revocation at final commit fence","FORBIDDEN",()->service.ingest(admin,revoke));denyAt.set(-1);
            check("revoked write leaves data unchanged",number(service.read(admin,"roster","class-a").get("version"))==2);
            var otherCatalog=service.describe(other);enable(service,other,"roster.business",true);
            check("tenant isolation for stored data",arr(service.read(other,"roster","class-a"),"records").isEmpty());
            var wrong=map(Json.parse(request(service,admin,"roster.adapter",2,"caller-mismatch-01",rosterCsv)));wrong.put("expectedCaller",otherCatalog.get("caller"));
            denied("active account precondition enforced","IDENTITY_CHANGED",()->service.ingest(admin,Json.write(wrong)));
            enable(service,admin,"roster.connector",false);
            denied("disabled connector denies imports","PLUGIN_DISABLED",()->service.ingest(admin,request(service,admin,"roster.connector",2,"disabled-source-01",Map.of())));
            check("stopping a source retains business access",arr(service.read(admin,"roster","class-a"),"records").size()==4);
            pid=number(service.read(admin,"roster","class-a").get("pid"));enable(service,admin,"roster.business",false);
            check("stop actually terminates business child",ProcessHandle.of(pid).map(p->!p.isAlive()).orElse(true));
            check("business stop disables dependent adapter",!Boolean.TRUE.equals(status(service.describe(admin),"roster.adapter").get("effective")));
            check("business stop preserves adapter desire",Boolean.TRUE.equals(status(service.describe(admin),"roster.adapter").get("enabled")));
            check("other business remains available",arr(service.read(admin,"attendance","class-a"),"records").size()==4);
        } finally {service.close();states.close();}
        check("closing service releases process slots",number(ProcessProvider.metrics().get("activeProcesses"))==0);
        try(FilePluginStateStore restoredState=new FilePluginStateStore(data.resolve("states"),1024);
            IngestionService restarted=new IngestionService(root,new FileRecordStore(data.resolve("records")),restoredState,i->{},config)) {
            check("restart preserves disabled state",!Boolean.TRUE.equals(status(restarted.describe(admin),"roster.business").get("enabled")));
            check("restart preserves source desired state",Boolean.TRUE.equals(status(restarted.describe(admin),"roster.adapter").get("enabled")));
            enable(restarted,admin,"roster.business",true);enable(restarted,admin,"roster.connector",true);
            check("restart preserves imported data",arr(restarted.read(admin,"roster","class-a"),"records").size()==4);
            String old=firstRequest;
            denied("old JVM instance rejected","STALE_COMPOSITION",()->restarted.ingest(admin,old));
            check("same idempotency key replays after restart",Boolean.TRUE.equals(restarted.ingest(admin,request(restarted,admin,"roster.connector",0,"roster-api-0001",Map.of())).get("replayed")));
        }
        Path foreignRoot=data.resolve("node-runtime");Files.createDirectories(foreignRoot.resolve("ingestion"));
        var document=map(Json.parse(Files.readString(root.resolve("ingestion/catalog.json"))));
        for(Object p:arr(document,"plugins"))if("roster.business".equals(map(p).get("id")))map(p).put("launcher",Json.obj("argv",List.of("node",Path.of(args[2]).toAbsolutePath().toString()),"cwd",".","env",Map.of(),"requires",List.of()));
        Files.writeString(foreignRoot.resolve("ingestion/catalog.json"),Json.write(document));
        // Only the foreign business is activated, so other launcher requirements are not touched.
        try(IngestionService node=new IngestionService(foreignRoot,store,new MemoryPluginStateStore(),i->{},config)){
            enable(node,admin,"roster.business",true);var result=node.read(admin,"roster","class-a");
            check("real Node provider interoperates without Java change",number(obj(result,"summary").get("nodeRuntime"))==1);
            check("Node reads same persisted roster contract",arr(result,"records").size()==4);
        }
        check("all final child processes released",number(ProcessProvider.metrics().get("activeProcesses"))==0);
        System.out.println("RESULT "+checks+" ingestion component checks PASS");
    }
}
