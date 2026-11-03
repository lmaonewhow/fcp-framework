import fcp.*;
import java.nio.file.*;
import java.util.*;
import static fcp.Schema.*;

/** Timeout fixture allows cold interpreter startup on contended CI; the deliberate stall still exceeds it. */
public final class ProcessBoundsRegression {
    static final List<Object> results=new ArrayList<>();
    interface Test{void run()throws Exception;}
    static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
    static void run(String n,Test t){try{t.run();results.add(Json.obj("name",n,"status","PASS"));}catch(Throwable e){results.add(Json.obj("name",n,"status","FAIL","error",e.toString()));}}
    static void denied(String code,Runnable f){try{f.run();throw new AssertionError("Expected "+code);}catch(FcpException e){check(e.code.equals(code),e.code);}}
    static Object plan(){return Json.obj("protocol","fcp/0.3","kind","composition","id","test.app","modules",List.of("child"),"exports",Json.obj("child","child/api"));}
    static Map<String,Object> invoke(Engine e,String s,Map<String,Object> input){return map(e.invoke(s,"child","run",input,Map.of(),1L));}
    public static void main(String[] args)throws Exception{
        if(!"synthetic-canary".equals(System.getenv("FCP_TEST_SECRET")))throw new IllegalStateException("Set FCP_TEST_SECRET=synthetic-canary for the environment isolation test");
        Path root=Files.createTempDirectory("fcp-process-probe");
        Files.writeString(root.resolve("probe.py"),"""
            import json, os, sys, time
            for line in sys.stdin:
                request=json.loads(line);p=request['params']
                if p.get('slow'): time.sleep(6)
                if p.get('stderr'): sys.stderr.write('x'*2000000);sys.stderr.flush()
                result={'pid':os.getpid(),'secret':os.environ.get('FCP_TEST_SECRET')}
                if p.get('oversize'): result['large']='x'*1048576
                print(json.dumps({'jsonrpc':'2.0','id':'wrong' if p.get('badid') else request['id'],'result':result}),flush=True)
            """);
        Registry r=new Registry();r.register("contract",Json.obj("id","probe.v1","operations",Json.obj("run",Json.obj("input",Json.obj("type","object"),"output",Json.obj("type","object"),"effect","read"))));
        r.register("module",Json.obj("protocol","fcp/0.3","kind","module","id","child","version","1","provides",Json.obj("api","probe.v1"),"requires",Map.of(),"runtime",Json.obj("provider","stdio","launcher","test","timeout",4,"idleTimeoutMs",5000)));
        Map<String,Object> launchers=Json.obj("test",Json.obj("argv",List.of("{python}","-I","-u","{root}/probe.py")));
        r.factories.put("stdio",(m,s)->new ProcessProvider(m,root,launchers));
        try(Engine e=new Engine(r,null)){
            for(String s:List.of("a","b","c"))e.publish(s,plan(),0);
            run("providers_are_lazy_without_child_processes",()->check(number(ProcessProvider.metrics().get("activeProcesses"))==0,"eager process"));
            final long[] pid={0};
            run("environment_secrets_not_inherited",()->{var out=invoke(e,"a",Map.of());pid[0]=number(out.get("pid"));check(out.get("secret")==null,"secret leaked");});
            run("bounded_global_process_quota",()->{invoke(e,"b",Map.of());denied("BUSY",()->invoke(e,"c",Map.of()));check(number(ProcessProvider.metrics().get("activeProcesses"))==2,"quota");});
            run("idle_epoch_does_not_kill_reused_process",()->{for(int i=0;i<5;i++){Thread.sleep(100);check(number(invoke(e,"a",Map.of()).get("pid"))==pid[0],"timer raced with reuse");}});
            run("idle_reaper_releases_slots",()->{Thread.sleep(6500);check(number(ProcessProvider.metrics().get("activeProcesses"))==0,"slots retained");});
            run("expired_child_restarts",()->check(number(invoke(e,"c",Map.of()).get("pid"))!=pid[0],"pid reused"));
            run("timeout_stops_child",()->denied("DEADLINE",()->invoke(e,"c",Json.obj("slow",true))));
            run("timeout_does_not_poison_future_calls",()->check(number(invoke(e,"c",Map.of()).get("pid"))>0,"recovery"));
            run("stdout_frame_limit_enforced",()->denied("FRAME_LIMIT",()->invoke(e,"c",Json.obj("oversize",true))));
            run("protocol_id_mismatch_rejected",()->denied("PROTOCOL",()->invoke(e,"c",Json.obj("badid",true))));
            run("large_stderr_drains_without_retention_or_deadlock",()->check(number(invoke(e,"c",Json.obj("stderr",true)).get("pid"))>0,"stderr"));
        }
        run("close_reclaims_all_child_slots",()->check(number(ProcessProvider.metrics().get("activeProcesses"))==0,"leak"));
        run("io_threads_expire_after_idle",()->{long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);while(number(ProcessProvider.metrics().get("ioThreads"))!=0&&System.nanoTime()<deadline)Thread.sleep(100);check(number(ProcessProvider.metrics().get("ioThreads"))==0,"threads retained");});
        Files.deleteIfExists(root.resolve("probe.py"));Files.deleteIfExists(root);
        long failed=results.stream().filter(v->map(v).get("status").equals("FAIL")).count();System.out.println(Json.write(Json.obj("suite","process-bounds","passed",results.size()-failed,"failed",failed,"tests",results)));if(failed>0)System.exit(1);
    }
}
