import fcp.*;
import dev.fcp.dual.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static fcp.Schema.*;

/** Portable core paths and real child-process I/O. Does not emulate a RuoYi application. */
public final class CompatibilityRegression {
    static final List<Object> tests=new ArrayList<>();
    interface Test{void run()throws Exception;}
    static void run(String n,Test t){try{t.run();tests.add(Json.obj("name",n,"status","PASS"));}catch(Throwable e){tests.add(Json.obj("name",n,"status","FAIL","error",e.toString()));}}
    static void ok(boolean yes,String why){if(!yes)throw new AssertionError(why);}
    static void denied(String code,Runnable body){try{body.run();throw new AssertionError("Expected "+code);}catch(FcpException e){ok(e.code.equals(code),"Expected "+code+" got "+e.code);}}
    static Path root;
    static void frame(String variant,String expected)throws Exception {
        Path runtime=DualFlowRegression.copyRuntime();
        String behavior=switch(variant){
            case "extra"->"reply['unexpected']=1";
            case "wrong-id"->"reply['id']='wrong'";
            case "both"->"reply['error']={'code':-32000,'message':'bad'}";
            case "error-string"->"reply.pop('result'); reply['error']={'code':'-32000','message':'bad'}";
            case "partial"->"sys.stdout.write(json.dumps(reply)); sys.stdout.flush(); sys.exit(0)";
            case "utf8"->"sys.stdout.buffer.write(b'\\xff\\n');sys.stdout.buffer.flush();sys.exit(0)";
            case "large"->"sys.stdout.write('x'*1048576+'\\n');sys.stdout.flush();sys.exit(0)";
            default->throw new IllegalArgumentException();
        };
        String source="import sys,json,repair\nfor raw in sys.stdin:\n q=json.loads(raw)\n reply={'jsonrpc':'2.0','id':q['id'],'result':repair.plan(q['params'])}\n "+behavior+"\n print(json.dumps(reply),flush=True)\n";
        Files.writeString(runtime.resolve("plugins/probe.py"),source);
        Map<String,Object> launch=map(Json.parse(Files.readString(runtime.resolve("apps/launchers.json"))));arr(obj(launch,"repair.python"),"argv").set(1,"{root}/plugins/probe.py");
        Files.writeString(runtime.resolve("apps/launchers.json"),Json.write(launch));
        try(DualFlowRegression.Fixture f=new DualFlowRegression.Fixture(runtime,Files.createTempDirectory("fcp-frame-").resolve("records.json"))){
            denied(expected,()->f.create("repair",DualFlowRegression.repair(),DualFlowRegression.STAFF));
            ok(f.store.list("tenant-a","repair").isEmpty(),"Malformed response was committed");
        }
    }
    public static void main(String[] args)throws Exception {
        root=Path.of(args[0]).toAbsolutePath().normalize();DualFlowRegression.root=root;
        run("relative_runtime_directory",()->{Path relative=Path.of("").toAbsolutePath().relativize(root);try(DualFlowRegression.Fixture f=new DualFlowRegression.Fixture(relative,Files.createTempDirectory("fcp-relative-").resolve("r.json"))){f.create("repair",DualFlowRegression.repair(),DualFlowRegression.STAFF);}});
        run("unicode_and_space_runtime_directory",()->{Path copy=DualFlowRegression.copyRuntime(),target=copy.resolveSibling(copy.getFileName()+" 中文 空格");Files.move(copy,target);try(DualFlowRegression.Fixture f=new DualFlowRegression.Fixture(target,Files.createTempDirectory("fcp-path-").resolve("r.json"))){f.create("repair",DualFlowRegression.repair(),DualFlowRegression.STAFF);}});
        run("java_and_python_supplementary_unicode_titles",()->{try(DualFlowRegression.Fixture f=new DualFlowRegression.Fixture()){String title="🚀".repeat(100);Map<String,Object> p=DualFlowRegression.purchase(),r=DualFlowRegression.repair();p.put("title",title);r.put("title",title);ok(obj(f.create("purchase",p,DualFlowRegression.STAFF),"body").get("title").equals(title),"Java Unicode mismatch");ok(obj(f.create("repair",r,DualFlowRegression.STAFF),"body").get("title").equals(title),"Python Unicode mismatch");}});
        run("legacy_http_envelope_still_valid",()->HostRequest.parse(Json.write(Json.obj("input",Map.of(),"generation",1,"instance",UUID.randomUUID().toString())),false));
        run("matching_caller_precondition",()->{Map<String,Object> r=Json.obj("expectedCaller",Json.obj("scope","tenant-a","subject","101"));HostRequest.checkCaller(r,"tenant-a","101");});
        run("switched_account_rejected_before_write",()->denied("IDENTITY_CHANGED",()->HostRequest.checkCaller(Json.obj("expectedCaller",Json.obj("scope","tenant-a","subject","101")),"tenant-a","102")));
        run("switched_scope_rejected_before_write",()->denied("IDENTITY_CHANGED",()->HostRequest.checkCaller(Json.obj("expectedCaller",Json.obj("scope","tenant-a","subject","101")),"tenant-b","101")));
        run("caller_precondition_cannot_inject_permissions",()->denied("INVALID_INPUT",()->HostRequest.parse(Json.write(Json.obj("input",Map.of(),"generation",1,"instance",UUID.randomUUID().toString(),"expectedCaller",Json.obj("scope","tenant-a","subject","101","admin",true))),false)));
        run("context_cross_thread_requires_explicit_delegation",()->{
            AtomicReference<Engine.Context> saved=new AtomicReference<>();
            // Check the owner thread rule while a provider invocation is still live.
            Registry r=new Registry();r.register("contract",Json.obj("id","thread.v1","operations",Json.obj("run",Json.obj("input",Json.obj("type","object"),"output",Map.of()))));
            r.register("module",Json.obj("protocol","fcp/0.3","kind","module","id","one","version","1","provides",Json.obj("api","thread.v1"),"runtime",Json.obj("provider","one")));
            r.factories.put("one",(m,s)->(c,e,o,i)->{ExecutorService pool=Executors.newSingleThreadExecutor();try{pool.submit(()->denied("CONTEXT_THREAD",()->c.atomic(()->"write"))).get(3,TimeUnit.SECONDS);}catch(Exception error){throw new RuntimeException(error);}finally{pool.shutdownNow();}return true;});
            try(Engine e=new Engine(r,null)){e.publish("test",Json.obj("protocol","fcp/0.3","kind","composition","id","test.app","modules",List.of("one"),"exports",Json.obj("one","one/api")),0);e.invoke("test","one","run",Map.of(),Map.of(),1L);}
        });
        for(String bad:List.of("extra","wrong-id","both","error-string"))run("reject_rpc_"+bad,()->frame(bad,"PROTOCOL"));
        for(String bad:List.of("partial","utf8"))run("reject_rpc_"+bad,()->frame(bad,"TRANSPORT"));
        run("reject_oversized_line_with_lf_counted",()->frame("large","FRAME_LIMIT"));
        run("invalid_plugin_timeout_refused_at_publish",()->{Registry r=Registry.load(root);Map<String,Object> doc=r.modules.get("repair.rules");obj(doc,"runtime").put("timeout",-1);denied("MANIFEST",()->new ProcessProvider(doc,root,Json.obj("repair.python",Json.obj("argv",List.of("{python}")))));});
        run("version_exhaustion_cannot_write_then_fail_output",()->{
            AtomicInteger writes=new AtomicInteger();
            Map<String,Object> record=Json.obj("id","existing","scope","tenant-a","kind","purchase","version",1000000000,"body",Json.obj("title","t","amount","1.00","owner","101","status","draft","decision",""));
            RecordStore store=new RecordStore(){public Map<String,Object> get(String s,String k,String i){return record;}public List<Object> list(String s,String k){return List.of(record);}public List<Object> history(String s,String k,String i){return List.of();}public Map<String,Object> replay(String s,String u,String q,String f){return null;}public Map<String,Object> commit(String s,String k,String u,String q,String f,String i,long v,Map<String,Object>b,Map<String,Object>a){writes.incrementAndGet();return Json.obj("id",i,"scope",s,"kind",k,"version",v+1,"body",b);}};
            try(Engine e=DualSystem.create(root,store,null)){e.publish("tenant-a","ruoyi.java-only",0);denied("VERSION_EXHAUSTED",()->e.invoke("tenant-a","purchase","submit",Json.obj("id","existing","expectedVersion",1000000000,"requestKey","exhaust-test","data",Map.of()),Json.obj("subject","101","viewAll",false),1L));ok(writes.get()==0,"Write occurred past the output limit");}
        });
        long failed=tests.stream().filter(t->map(t).get("status").equals("FAIL")).count();System.out.println(Json.write(Json.obj("suite","runtime-compatibility","scope","real JVM/Python, Linux paths, wire and identity boundaries; no RuoYi runtime","passed",tests.size()-failed,"failed",failed,"tests",tests)));if(failed>0)System.exit(1);
    }
}
