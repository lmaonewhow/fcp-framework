import dev.fcp.dual.FileRecordStore;
import dev.fcp.school.SchoolFacade;
import fcp.*;
import fcp.state.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static fcp.Schema.*;

/** Real filesystem/concurrency/Python component tests; no claim of Spring or MySQL execution. */
public final class ProductionRegression {
    private static int checks;
    static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    static void deny(String code,Runnable action,String name){
        try{action.run();throw new AssertionError("Expected "+code+": "+name);}
        catch(FcpException failure){check(failure.code.equals(code),name+" -> "+failure.code);}
    }
    static SchoolFacade.Identity admin(String scope){return new SchoolFacade.Identity(scope,"100",Set.of(
        "fcp:eval:list","fcp:eval:get","fcp:eval:history","fcp:eval:analyze","fcp:eval:create","fcp:eval:manage","fcp:eval:allClasses","fcp:ui:admin"),Set.of());}
    static String envelope(Map<String,Object> snapshot,Map<String,Object> input){return Json.write(Json.obj(
        "instance",snapshot.get("instance"),"generation",snapshot.get("generation"),"expectedCaller",snapshot.get("caller"),"input",input));}
    static String create(Map<String,Object> snapshot,String requestKey){return envelope(snapshot,Json.obj("requestKey",requestKey,"data",Json.obj(
        "student","synthetic-student","classId","class-a","indicator","课堂参与","score",88,"comment","Synthetic lifecycle test")));}
    static void competingJvm(Path directory,String label)throws Exception{
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        Process child=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),"ProductionRegression","--lock-probe",directory.toString()).redirectErrorStream(true).start();
        if(!child.waitFor(10,TimeUnit.SECONDS)){child.destroyForcibly();throw new AssertionError("Lock probe timed out");}
        check(child.exitValue()==23,label);
    }
    static void durable(Path root,Path temp)throws Exception{
        Path stateDir=temp.resolve("durable");var key=new PluginStateStore.Key("tenant-000000","evaluation.stats");
        var identity=admin(key.scope());String id;long revision;
        try(var states=new FilePluginStateStore(stateDir,8);
            var facade=new SchoolFacade(root,new FileRecordStore(temp.resolve("records.json")),null,states,false,8,x->{})){
            Map<String,Object> initial=facade.describe(identity);
            check(Boolean.FALSE.equals(initial.get("pythonEnabled")),"new production scope defaults disabled");
            deny("PLUGIN_DISABLED",()->facade.invoke(identity,"analyze",envelope(initial,Json.obj())),"disabled provider inaccessible");
            var created=map(facade.invoke(identity,"create",create(initial,"durable-create-001")));id=str(created.get("id"));
            var enabled=facade.compose(identity,envelope(initial,Json.obj("enabled",true)));
            var stats=map(facade.invoke(identity,"analyze",envelope(enabled,Json.obj())));
            check("python-stdlib".equals(stats.get("engine")) && number(stats.get("count"))==1,"durable mode executes real Python child");
            var disabled=facade.compose(identity,envelope(enabled,Json.obj("enabled",false)));
            revision=number(disabled.get("stateRevision"));
            check(revision==3,"state revisions increase independently of instance generation");
            competingJvm(stateDir,"independent JVM excluded by filesystem lock");
            try(var ignored=new FilePluginStateStore(stateDir,8)){throw new AssertionError("second store opened");}
            catch(java.io.IOException|java.nio.channels.OverlappingFileLockException expected){check(true,"second writer excluded by OS lock");}
            competingJvm(stateDir,"failed same-JVM open does not release cross-process lock");
            deny("STALE_COMPOSITION",()->states.compareAndSet(key,1,true,"100"),"stale persisted CAS rejected");
        }
        try(var states=new FilePluginStateStore(stateDir,8);
            var facade=new SchoolFacade(root,new FileRecordStore(temp.resolve("records.json")),null,states,true,8,x->{})){
            var after=facade.describe(identity);
            check(Boolean.FALSE.equals(after.get("pythonEnabled")),"disabled state survives complete store and engine restart");
            check(number(after.get("stateRevision"))==revision,"persisted revision survives restart");
            var record=map(facade.invoke(identity,"get",envelope(after,Json.obj("id",id))));
            check(id.equals(record.get("id")),"business record survives independent state restart");
            states.compareAndSet(key,revision,true,"deployment");
            deny("STALE_COMPOSITION",()->facade.invoke(identity,"list",envelope(after,Json.obj())),"external desired-state update fences old requests");
            check(Boolean.TRUE.equals(facade.describe(identity).get("pythonEnabled")),"external update reconciles on next request");
        }
        if(Files.getFileStore(stateDir).supportsFileAttributeView("posix")){
            check(Files.getPosixFilePermissions(stateDir).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")),"state directory owner-only");
            check(Files.getPosixFilePermissions(stateDir.resolve("plugin-state.json")).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")),"persisted state owner-only");
        }
        Files.writeString(stateDir.resolve("plugin-state.json"),"{corrupted");
        try(var ignored=new FilePluginStateStore(stateDir,8)){throw new AssertionError("corrupt state accepted");}
        catch(RuntimeException|java.io.IOException expected){check(true,"corrupt state blocks startup without enabling defaults");}
    }
    static void concurrency(Path root,Path temp)throws Exception{
        var identity=admin("tenant-race");var memory=new MemoryPluginStateStore();
        try(var facade=new SchoolFacade(root,new FileRecordStore(temp.resolve("races.json")),null,memory,true,4,x->{})){
            var snapshot=facade.describe(identity);String body=envelope(snapshot,Json.obj("enabled",false));
            var start=new CountDownLatch(1);var passed=new AtomicInteger();var conflicts=new AtomicInteger();var error=new AtomicReference<Throwable>();
            Runnable action=()->{try{start.await();facade.compose(identity,body);passed.incrementAndGet();}catch(FcpException e){if(e.code.equals("STALE_COMPOSITION"))conflicts.incrementAndGet();else error.set(e);}catch(Throwable e){error.set(e);}};
            Thread one=new Thread(action),two=new Thread(action);one.start();two.start();start.countDown();one.join(10000);two.join(10000);
            check(!one.isAlive()&&!two.isAlive()&&error.get()==null,"concurrent publishers finish without deadlock");
            check(passed.get()==1&&conflicts.get()==1,"only one publisher wins an expected-generation race");
            check(memory.getOrCreate(new PluginStateStore.Key(identity.scope(),"evaluation.stats"),true).revision()==2,"rejected publisher creates no extra revision");
        }
        var both=new CountDownLatch(2);var release=new CountDownLatch(1);var failures=new ConcurrentLinkedQueue<Throwable>();
        try(var facade=new SchoolFacade(root,new FileRecordStore(temp.resolve("reads.json")),(ctx,target,op)->{
            if(op.equals("list")){both.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("serialized read");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}
        })){
            var snapshot=facade.describe(identity);String body=envelope(snapshot,Json.obj());
            Runnable read=()->{try{facade.invoke(identity,"list",body);}catch(Throwable failure){failures.add(failure);}};
            Thread one=new Thread(read),two=new Thread(read);one.start();two.start();boolean concurrent=both.await(3,TimeUnit.SECONDS);release.countDown();one.join(6000);two.join(6000);
            check(concurrent&&failures.isEmpty(),"read leases preserve concurrent invocation admission");
        }
    }
    static void failures(Path root,Path temp)throws Exception{
        var identity=admin("tenant-faults");var memory=new MemoryPluginStateStore();var unavailable=new AtomicBoolean(false);
        PluginStateStore failing=new PluginStateStore(){
            public State getOrCreate(Key key,boolean initial){if(unavailable.get())throw new FcpException("STATE_UNAVAILABLE","Injected outage");return memory.getOrCreate(key,initial);}
            public State compareAndSet(Key key,long revision,boolean enabled,String actor){if(unavailable.get())throw new FcpException("STATE_UNAVAILABLE","Injected outage");return memory.compareAndSet(key,revision,enabled,actor);}
        };
        try(var facade=new SchoolFacade(root,new FileRecordStore(temp.resolve("faults.json")),null,failing,false,1,x->{})){
            var snapshot=facade.describe(identity);unavailable.set(true);
            deny("STATE_UNAVAILABLE",()->facade.invoke(identity,"list",envelope(snapshot,Json.obj())),"state outage cannot use cached authorization");
            deny("STATE_UNAVAILABLE",()->facade.compose(identity,envelope(snapshot,Json.obj("enabled",true))),"state outage cannot acknowledge enable");
            unavailable.set(false);check(Boolean.FALSE.equals(facade.describe(identity).get("pythonEnabled")),"state outage preserves previous desired state");
            deny("RESOURCE_LIMIT",()->facade.describe(admin("tenant-overflow")),"tenant snapshot cardinality bounded");
        }
        var revoked=new AtomicBoolean(false);
        try(var facade=new SchoolFacade(root,new FileRecordStore(temp.resolve("management.json")),null,new MemoryPluginStateStore(),true,4,x->{if(revoked.get())throw new FcpException("FORBIDDEN","revoked");})){
            var snapshot=facade.describe(identity);revoked.set(true);
            deny("FORBIDDEN",()->facade.compose(identity,envelope(snapshot,Json.obj("enabled",false))),"management grant rechecked before durable mutation");
            check(Boolean.TRUE.equals(facade.describe(identity).get("pythonEnabled")),"denied management call leaves composition unchanged");
        }
        var checksAtBoundary=new AtomicInteger();
        try(var facade=new SchoolFacade(root,new FileRecordStore(temp.resolve("revoked-write.json")),(context,target,operation)->{
            if(operation.equals("create")&&checksAtBoundary.incrementAndGet()>1)throw new FcpException("FORBIDDEN","Revoked between admission and commit");
        })){
            var snapshot=facade.describe(identity);
            deny("FORBIDDEN",()->facade.invoke(identity,"create",create(snapshot,"revoke-during-write-001")),"write grant rechecked at atomic effect boundary");
            var page=map(facade.invoke(identity,"list",envelope(snapshot,Json.obj())));
            check(arr(page,"items").isEmpty(),"revoked write produces no business row");
        }
        var small=new MemoryPluginStateStore(1);small.getOrCreate(new PluginStateStore.Key("tenant-a","stats"),false);
        deny("RESOURCE_LIMIT",()->small.getOrCreate(new PluginStateStore.Key("tenant-b","stats"),true),"volatile store cardinality bounded");
    }
    public static void main(String[] args)throws Exception{
        if(args.length==2&&args[0].equals("--lock-probe")){
            try(var ignored=new FilePluginStateStore(Path.of(args[1]),8)){System.exit(0);}
            catch(java.io.IOException expected){System.exit(23);}
            return;
        }
        Path root=Path.of(args[0]),temp=Files.createTempDirectory("fcp-hardening-");
        try{durable(root,temp);concurrency(root,temp);failures(root,temp);
            check(((Number)ProcessProvider.metrics().get("activeProcesses")).intValue()==0,"no Python child leaked after lifecycle tests");
            System.out.println("RESULT "+checks+" production-hardening component checks PASS; native integration NOT RUN");
        }finally{try(var files=Files.walk(temp)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
