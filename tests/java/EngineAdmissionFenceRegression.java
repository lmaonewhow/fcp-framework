import fcp.*;
import dev.fcp.dual.RecordStore;
import dev.fcp.school.SchoolSystem;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static fcp.Schema.*;

/** Deterministic memory/latch fixtures. Not JDBC, throughput, or native-host acceptance evidence. */
public final class EngineAdmissionFenceRegression {
    static java.util.function.Function<Store,RecordStore> backingFactory;
    static String requestPrefix="";
    static int checks;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("Fixture barrier timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}
    static Map<String,Object> attributes(){return Json.obj("subject","100","permissions",List.of("fcp:eval:list","fcp:eval:get","fcp:eval:history","fcp:eval:create"),"classes",List.of("class-a"),"allClasses",false);}
    static Object invoke(Engine engine,String scope,Map<String,Object> snapshot,String op,Map<String,Object> input){return engine.invoke(scope,"evaluation",op,input,attributes(),number(snapshot.get("generation")),str(snapshot.get("instance")));}
    static Map<String,Object> create(String key){return Json.obj("requestKey",requestPrefix+key,"data",Json.obj("student","fence-fixture","classId","class-a","indicator","\u8bfe\u5802\u53c2\u4e0e","score",80,"comment","Concurrency test fixture"));}
    static final class Store implements RecordStore {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicInteger calls=new AtomicInteger(),active=new AtomicInteger(),peak=new AtomicInteger();
        final Map<String,Map<String,Object>> rows=new ConcurrentHashMap<>();
        final int blockAt;
        final RecordStore backing;
        Store(boolean block){this(block?1:0);}
        Store(int blockedCall){blockAt=blockedCall;backing=backingFactory==null?null:backingFactory.apply(this);}
        public Map<String,Object> get(String scope,String kind,String id){if(backing!=null)return backing.get(scope,kind,id);var row=rows.get(scope+"/"+id);if(row==null)throw new FcpException("NOT_FOUND","Fixture row absent");return row;}
        public List<Object> list(String scope,String kind){if(backing!=null)return backing.list(scope,kind);return rows.values().stream().filter(r->scope.equals(r.get("scope"))).map(r->(Object)r).toList();}
        public List<Object> history(String scope,String kind,String id){return backing==null?List.of():backing.history(scope,kind,id);}
        public Map<String,Object> replay(String scope,String subject,String key,String fingerprint){return backing==null?null:backing.replay(scope,subject,key,fingerprint);}
        public Map<String,Object> commit(String scope,String kind,String subject,String key,String fingerprint,String id,long expected,Map<String,Object> body,Map<String,Object> audit){
            int call=calls.incrementAndGet();peak.accumulateAndGet(active.incrementAndGet(),Math::max);
            try{
                if(backing!=null)return backing.commit(scope,kind,subject,key,fingerprint,id,expected,body,audit);
                if(call==blockAt){entered.countDown();await(release);}
                var row=Json.obj("scope",scope,"kind",kind,"id",id,"version",1,"body",body);rows.put(scope+"/"+id,row);return row;
            }finally{active.decrementAndGet();}
        }
    }
    static void cleanup(Store store,ExecutorService pool)throws Exception{store.release.countDown();pool.shutdownNow();if(!pool.awaitTermination(10,TimeUnit.SECONDS))throw new AssertionError("Fixture workers did not finish");}
    static void admissionAndPublication(Path root)throws Exception{
        Store store=new Store(true);CountDownLatch secondDispatch=new CountDownLatch(1);AtomicInteger secondChecks=new AtomicInteger();
        try(Engine engine=SchoolSystem.create(root,store,(ctx,target,op)->{if(ctx.scope().equals("tenant-b")&&op.equals("create")&&secondChecks.incrementAndGet()==1)secondDispatch.countDown();})){
            var a=engine.publish("tenant-a","school.java-only",0);var b=engine.publish("tenant-b","school.java-only",0);ExecutorService pool=Executors.newFixedThreadPool(4);
            try{
                Future<?> first=pool.submit(()->invoke(engine,"tenant-a",a,"create",create("commit-admission-first")));await(store.entered);
                Future<?> reader=pool.submit(()->invoke(engine,"tenant-a",a,"list",Json.obj("limit",25)));
                try{reader.get(2,TimeUnit.SECONDS);}catch(TimeoutException e){throw new AssertionError("Read admission/result waits behind unrelated durable commit",e);}
                check(true,"read completes while commit action is blocked");
                Future<?> descriptor=pool.submit(()->engine.describe("tenant-b"));descriptor.get(2,TimeUnit.SECONDS);check(true,"other scope metadata remains available");
                Future<?> second=pool.submit(()->invoke(engine,"tenant-b",b,"create",create("commit-admission-second")));await(secondDispatch);
                Future<?> publish=pool.submit(()->engine.publish("tenant-a","school.java-only",1));Thread.sleep(100);
                check(store.calls.get()==1&&!second.isDone(),"commit actions remain globally serialized");check(!publish.isDone(),"publication cannot cross active commit fence");
                store.release.countDown();first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);publish.get(10,TimeUnit.SECONDS);
                check(store.calls.get()==2&&store.peak.get()==1,"two writes finish without overlapping atomic actions");check(number(engine.describe("tenant-a").get("generation"))==2,"publication completes after commit");
            }finally{cleanup(store,pool);}
        }
    }
    static void closeFence(Path root)throws Exception{
        Store store=new Store(true);Engine engine=SchoolSystem.create(root,store,null);var snapshot=engine.publish("tenant-close","school.java-only",0);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<?> write=pool.submit(()->invoke(engine,"tenant-close",snapshot,"create",create("commit-close-first")));await(store.entered);Future<?> close=pool.submit(engine::close);Thread.sleep(100);
            check(!close.isDone(),"close cannot invalidate a durable action already inside the fence");store.release.countDown();write.get(10,TimeUnit.SECONDS);close.get(10,TimeUnit.SECONDS);check(store.calls.get()==1,"known committed result survives concurrent close");
        }finally{cleanup(store,pool);engine.close();}
    }
    static void committedAcknowledgement(Path root)throws Exception{
        Store store=new Store(2);CountDownLatch firstCommitted=new CountDownLatch(1),returnFirst=new CountDownLatch(1);
        try(Engine engine=SchoolSystem.create(root,store,null)){
            Engine.Factory original=engine.registry.factories.get("evaluation-java");
            engine.registry.factories.put("evaluation-java",(manifest,scope)->{
                Engine.Provider delegate=original.create(manifest,scope);
                return new Engine.Provider(){
                    public Object invoke(Engine.Context ctx,String export,String op,Map<String,Object> input){
                        Object result=delegate.invoke(ctx,export,op,input);
                        if(scope.equals("tenant-ack-a")&&op.equals("create")){firstCommitted.countDown();await(returnFirst);}
                        return result;
                    }
                    public void close(){delegate.close();}
                };
            });
            var a=engine.publish("tenant-ack-a","school.java-only",0);var b=engine.publish("tenant-ack-b","school.java-only",0);ExecutorService pool=Executors.newFixedThreadPool(2);
            try{
                Future<?> first=pool.submit(()->invoke(engine,"tenant-ack-a",a,"create",create("commit-ack-first")));await(firstCommitted);
                Future<?> second=pool.submit(()->invoke(engine,"tenant-ack-b",b,"create",create("commit-ack-second")));await(store.entered);returnFirst.countDown();
                try{first.get(2,TimeUnit.SECONDS);}catch(TimeoutException e){throw new AssertionError("Known committed acknowledgement waits behind a later durable action",e);}
                check(true,"known committed acknowledgement completes during later blocked commit");
                store.release.countDown();second.get(10,TimeUnit.SECONDS);check(store.calls.get()==2&&store.peak.get()==1,"acknowledgement progress does not parallelize atomic actions");
            }finally{returnFirst.countDown();cleanup(store,pool);}
        }
    }
    static void publicationWins(Path root)throws Exception{
        Store store=new Store(false);CountDownLatch admitted=new CountDownLatch(1),release=new CountDownLatch(1);
        try(Engine engine=SchoolSystem.create(root,store,(ctx,target,op)->{if(op.equals("create")){admitted.countDown();await(release);}})){
            var snapshot=engine.publish("tenant-stale","school.java-only",0);ExecutorService pool=Executors.newFixedThreadPool(1);
            try{
                Future<?> write=pool.submit(()->invoke(engine,"tenant-stale",snapshot,"create",create("commit-stale-first")));await(admitted);engine.publish("tenant-stale","school.java-only",1);release.countDown();
                try{write.get(10,TimeUnit.SECONDS);throw new AssertionError("Stale commit accepted");}catch(ExecutionException e){check(e.getCause() instanceof FcpException f&&f.code.equals("STALE_COMPOSITION"),"publication before commit still rejects stale capability");}
                check(store.calls.get()==0,"stale action never reaches persistence");
            }finally{release.countDown();cleanup(store,pool);}
        }
    }
    static void slowPreparation(Path root)throws Exception{
        Store store=new Store(false);CountDownLatch preparing=new CountDownLatch(1),release=new CountDownLatch(1);
        try(Engine engine=SchoolSystem.create(root,store,null)){
            var snapshot=engine.publish("tenant-prepare","school.java-only",0);Engine.Factory original=engine.registry.factories.get("stdio");
            engine.registry.factories.put("stdio",(manifest,scope)->{preparing.countDown();await(release);return original.create(manifest,scope);});ExecutorService pool=Executors.newFixedThreadPool(2);
            try{
                Future<?> publish=pool.submit(()->engine.publish("tenant-prepare","school.full",1));await(preparing);Future<?> write=pool.submit(()->invoke(engine,"tenant-prepare",snapshot,"create",create("commit-during-prepare")));
                write.get(2,TimeUnit.SECONDS);check(store.calls.get()==1,"slow provider preparation does not hold commit fence");release.countDown();publish.get(10,TimeUnit.SECONDS);
            }finally{release.countDown();cleanup(store,pool);}
        }
    }
    public static void main(String[]args)throws Exception{
        Path root=Path.of(args[0]);String scenario=args.length>1?args[1]:"all";
        switch(scenario){
            case "admission" -> admissionAndPublication(root);
            case "close" -> closeFence(root);
            case "ack" -> committedAcknowledgement(root);
            case "publication" -> publicationWins(root);
            case "prepare" -> slowPreparation(root);
            case "all" -> {admissionAndPublication(root);closeFence(root);committedAcknowledgement(root);publicationWins(root);slowPreparation(root);}
            default -> throw new IllegalArgumentException("Unknown scenario: "+scenario);
        }
        System.out.println("RESULT "+checks+" admission/commit-fence checks PASS");
    }
}
