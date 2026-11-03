import fcp.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static fcp.Schema.*;

/** Deterministic concurrency probes. Latches prove overlap; timing is only a deadlock watchdog. */
public final class KernelRegression {
    private static final List<Object> results=new ArrayList<>();
    interface Test {void run() throws Exception;}
    static void expect(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void await(CountDownLatch latch)throws Exception{expect(latch.await(3,TimeUnit.SECONDS),"latch not reached");}
    static void blocked(CountDownLatch latch){try{if(!latch.await(6,TimeUnit.SECONDS))throw new AssertionError("release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}
    static Object call(Engine e,String cap){return e.invoke("test",cap,"run",Map.of(),Map.of(),1L);}
    static void denied(String code,Runnable op){try{op.run();throw new AssertionError("Expected "+code);}catch(FcpException e){expect(e.code.equals(code),e.code);}}
    static Registry registry(){Registry r=new Registry();r.register("contract",Json.obj("id","test.v1","operations",Json.obj("run",Json.obj("input",Json.obj("type","object"),"output",Map.of(),"effect","write"))));return r;}
    static void module(Registry r,String id,String provider){r.register("module",Json.obj("protocol","fcp/0.3","kind","module","id",id,"version","1","provides",Json.obj("api","test.v1"),"requires",Map.of(),"runtime",Json.obj("provider",provider)));}
    static Map<String,Object> plan(String... modules){Map<String,Object> exports=new LinkedHashMap<>();for(String m:modules)exports.put(m,m+"/api");return Json.obj("protocol","fcp/0.3","kind","composition","id","test.app","modules",List.of(modules),"exports",exports);}
    static void run(String name,Test test){long start=System.nanoTime();try{test.run();results.add(Json.obj("name",name,"status","PASS","elapsedMs",(System.nanoTime()-start)/1000000));}catch(Throwable error){results.add(Json.obj("name",name,"status","FAIL","error",error.toString()));}}
    static void parallel()throws Exception{
        Registry r=registry();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        module(r,"slow","slow");module(r,"fast","fast");
        r.factories.put("slow",(m,s)->(c,e,o,i)->{entered.countDown();blocked(release);return "slow";});
        r.factories.put("fast",(m,s)->(c,e,o,i)->"fast");
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try(Engine e=new Engine(r,null)){e.publish("test",plan("slow","fast"),0);Future<Object> slow=pool.submit(()->call(e,"slow"));try{await(entered);Future<Object> fast=pool.submit(()->call(e,"fast"));expect("fast".equals(fast.get(1500,TimeUnit.MILLISECONDS)),"fast result");expect(!slow.isDone(),"slow must still be waiting");}finally{release.countDown();}slow.get(3,TimeUnit.SECONDS);}
        finally{release.countDown();pool.shutdownNow();}
    }
    static void retirement()throws Exception{
        Registry r=registry();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger closed=new AtomicInteger();
        module(r,"slow","slow");module(r,"fast","fast");r.factories.put("slow",(m,s)->new Engine.Provider(){public Object invoke(Engine.Context c,String e,String o,Map<String,Object> i){entered.countDown();blocked(release);expect(closed.get()==0,"provider closed while in-flight");return 1;}public void close(){closed.incrementAndGet();}});r.factories.put("fast",(m,s)->(c,e,o,i)->2);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try(Engine e=new Engine(r,null)){e.publish("test",plan("slow"),0);Future<?> old=pool.submit(()->denied("STALE_COMPOSITION",()->call(e,"slow")));try{await(entered);pool.submit(()->e.publish("test",plan("fast"),1)).get(1500,TimeUnit.MILLISECONDS);expect(closed.get()==0,"close must be deferred");}finally{release.countDown();}old.get(3,TimeUnit.SECONDS);expect(closed.get()==1,"retired provider must close exactly once");}
        finally{release.countDown();pool.shutdownNow();}
    }
    static void successfulCommit()throws Exception{
        Registry r=registry();CountDownLatch committed=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger writes=new AtomicInteger();module(r,"flow","flow");r.factories.put("flow",(m,s)->(c,e,o,i)->{Object result=c.atomic(writes::incrementAndGet);committed.countDown();blocked(release);return result;});ExecutorService pool=Executors.newFixedThreadPool(2);
        try(Engine e=new Engine(r,null)){e.publish("test",plan("flow"),0);Future<Object> old=pool.submit(()->call(e,"flow"));try{await(committed);pool.submit(()->e.publish("test",plan("flow"),1)).get(1500,TimeUnit.MILLISECONDS);}finally{release.countDown();}expect(number(old.get(3,TimeUnit.SECONDS))==1,"committed result must not become false stale failure");expect(writes.get()==1,"one write");}finally{release.countDown();pool.shutdownNow();}
    }
    static void authorizationRevoked()throws Exception{
        Registry r=registry();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean permitted=new AtomicBoolean(true);AtomicInteger writes=new AtomicInteger();module(r,"flow","flow");r.factories.put("flow",(m,s)->(c,e,o,i)->{entered.countDown();blocked(release);return c.atomic(writes::incrementAndGet);});ExecutorService pool=Executors.newSingleThreadExecutor();
        try(Engine e=new Engine(r,(c,t,o)->{if(!permitted.get())throw new FcpException("FORBIDDEN","revoked");})){e.publish("test",plan("flow"),0);Future<?> task=pool.submit(()->denied("FORBIDDEN",()->call(e,"flow")));try{await(entered);permitted.set(false);}finally{release.countDown();}task.get(3,TimeUnit.SECONDS);expect(writes.get()==0,"revoked actor must not commit");}finally{release.countDown();pool.shutdownNow();}
    }
    static void staleCommit()throws Exception{
        Registry r=registry();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger writes=new AtomicInteger();module(r,"flow","flow");r.factories.put("flow",(m,s)->(c,e,o,i)->{entered.countDown();blocked(release);return c.atomic(writes::incrementAndGet);});ExecutorService pool=Executors.newFixedThreadPool(2);
        try(Engine e=new Engine(r,null)){e.publish("test",plan("flow"),0);Future<?> task=pool.submit(()->denied("STALE_COMPOSITION",()->call(e,"flow")));try{await(entered);pool.submit(()->e.publish("test",plan("flow"),1)).get(1500,TimeUnit.MILLISECONDS);}finally{release.countDown();}task.get(3,TimeUnit.SECONDS);expect(writes.get()==0,"stale call must not commit");}finally{release.countDown();pool.shutdownNow();}
    }
    static void attributes(){Registry r=registry();module(r,"flow","flow");r.factories.put("flow",(m,s)->(c,e,o,i)->{try{list(c.attributes.get("roles")).add("admin");throw new AssertionError("roles mutable");}catch(UnsupportedOperationException good){}try{obj(c.attributes,"identity").put("subject","admin");throw new AssertionError("identity mutable");}catch(UnsupportedOperationException good){}return true;});try(Engine e=new Engine(r,null)){e.publish("test",plan("flow"),0);e.invoke("test","flow","run",Map.of(),Json.obj("roles",List.of("reader"),"identity",Json.obj("subject","alice")),1L);}}
    static void rollback(){Registry r=registry();AtomicInteger closed=new AtomicInteger();module(r,"stable","stable");module(r,"failed","failed");r.factories.put("stable",(m,s)->new Engine.Provider(){public Object invoke(Engine.Context c,String e,String o,Map<String,Object> i){return 7;}public void close(){closed.incrementAndGet();}});r.factories.put("failed",(m,s)->{throw new FcpException("BUILD_REQUIRED","no artifact");});try(Engine e=new Engine(r,null)){e.publish("test",plan("stable"),0);denied("BUILD_REQUIRED",()->e.publish("test",plan("stable","failed"),1));expect(number(e.describe("test").get("generation"))==1,"old composition remains");expect(number(call(e,"stable"))==7,"old provider remains");expect(closed.get()==0,"old provider retained");}expect(closed.get()==1,"one final disposal");}
    static void reuse(){Registry r=registry();AtomicInteger builds=new AtomicInteger(),closes=new AtomicInteger();module(r,"flow","flow");r.factories.put("flow",(m,s)->{builds.incrementAndGet();return new Engine.Provider(){public Object invoke(Engine.Context c,String e,String o,Map<String,Object> i){return 1;}public void close(){closes.incrementAndGet();}};});try(Engine e=new Engine(r,null)){e.publish("test",plan("flow"),0);for(int i=1;i<5;i++)e.publish("test",plan("flow"),i);expect(builds.get()==1,"immutable provider reused");expect(closes.get()==0,"shared provider remains alive");}expect(closes.get()==1,"final ref removed once");}
    public static void main(String[] args){String selected=args.length==0?"all":args[0];
        Map<String,Test> tests=new LinkedHashMap<>();tests.put("unrelated_java_runs_while_python_like_work_waits",KernelRegression::parallel);tests.put("retirement_drains_inflight_before_disposal",KernelRegression::retirement);tests.put("committed_response_survives_publication",KernelRegression::successfulCommit);tests.put("authorization_rechecked_at_commit",KernelRegression::authorizationRevoked);tests.put("stale_snapshot_cannot_commit",KernelRegression::staleCommit);tests.put("nested_identity_is_immutable",KernelRegression::attributes);tests.put("failed_publication_preserves_old_application",KernelRegression::rollback);tests.put("reused_provider_has_reference_counted_disposal",KernelRegression::reuse);
        tests.forEach((name,test)->{if(selected.equals("all")||selected.equals(name))run(name,test);});
        long failed=results.stream().filter(r->map(r).get("status").equals("FAIL")).count();System.out.println(Json.write(Json.obj("suite","kernel-regression","passed",results.size()-failed,"failed",failed,"tests",results)));if(failed>0)System.exit(1);
    }
}
