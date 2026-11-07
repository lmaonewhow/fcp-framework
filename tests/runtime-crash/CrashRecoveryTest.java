import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import fcp.*;
import fcp.control.*;
import fcp.host.*;
import fcp.plugin.*;
import fcp.runtime.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import static java.nio.file.StandardOpenOption.*;

/** Real abrupt JVM exits, production installer/coordinator/Engine, real Python/Node children.
 * Authorization is a host fixture. Journals are real files by default, or guarded dedicated MySQL.
 */
public final class CrashRecoveryTest {
    static final String PLUGIN="crashfixture", TENANT="crash-tenant";
    static final int CRASH_EXIT=86;
    static final List<String> POINTS=List.of("STAGED","VERIFIED","INTENT","READY","PUBLISHED","RECEIPT");
    static Path root;
    static String point, mode;
    static final class Host implements HostCapabilities {
        final boolean revoked;
        Host(boolean revoked){this.revoked=revoked;}
        public Descriptor descriptor(){return new Descriptor("crash-test","1","fcp.host/1","embedded-single-instance",Map.of());}
        public Clock clock(){return Clock.systemUTC();}
        public Decision authorize(TrustedContext c,Grant g){
            boolean denied=revoked&&g.pluginId().equals(PLUGIN)&&(g.operation().equals("enable")||g.operation().equals("invoke"));
            return new Decision(!denied,revoked?2:1);
        }
        public void audit(Audit event){}
    }
    static TrustedContext context(String tenant){return new TrustedContext(UUID.randomUUID().toString(),"crash-recovery",tenant,new TrustedContext.Principal(TrustedContext.Kind.USER,"test-admin"),1,Set.of("tenant:"+tenant),Instant.now().plusSeconds(60));}
    static void check(boolean condition,String description){if(!condition)throw new AssertionError(description);}
    static void durable(Path path,Object value)throws IOException {
        Files.createDirectories(path.getParent());
        try(FileChannel channel=FileChannel.open(path,CREATE,TRUNCATE_EXISTING,WRITE)){
            ByteBuffer bytes=ByteBuffer.wrap(Json.write(value).getBytes(StandardCharsets.UTF_8));while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
        }
        try(FileChannel channel=FileChannel.open(path.getParent(),READ)){channel.force(true);}
    }
    static Map<String,Object> read(Path path)throws IOException{return Schema.map(Json.parse(Files.readString(path)));}
    static void crash(String boundary)throws IOException {
        if(!mode.equals("crash")||!point.equals(boundary))return;
        durable(root.resolve("crash-marker.json"),Map.of("point",boundary,"mechanism","Runtime.halt","jvmPid",ProcessHandle.current().pid(),"exitCode",CRASH_EXIT));
        System.out.println("CRASH "+boundary+" via Runtime.halt("+CRASH_EXIT+")");System.out.flush();
        Runtime.getRuntime().halt(CRASH_EXIT);
        throw new AssertionError("Runtime.halt returned");
    }
    static final class CrashJournal implements StateJournal {
        final StateJournal delegate;
        CrashJournal(StateJournal delegate){this.delegate=delegate;}
        public Snapshot load()throws IOException{return delegate.load();}
        public Snapshot compareAndSet(long revision,String json)throws IOException {
            Snapshot persisted=delegate.compareAndSet(revision,json);
            Map<String,Object> operations=Schema.map(Schema.map(Json.parse(json)).get("operations"));
            for(Object raw:operations.values()){
                Map<String,Object> op=Schema.map(raw);
                if(op.get("phase").equals("INTENT"))crash("INTENT");
                if(op.get("phase").equals("COMPLETE")&&op.get("outcome").equals("APPLIED"))crash("RECEIPT");
            }
            return persisted;
        }
        public void close()throws IOException{delegate.close();}
    }
    static final class CrashRuntime implements RuntimePort {
        final EngineRuntimePort delegate;
        CrashRuntime(EngineRuntimePort delegate){this.delegate=delegate;}
        public void resetAdmissions(){delegate.resetAdmissions();}
        public void verifyInstalledPackage(PackageInstaller.InstalledPackage p){delegate.verifyInstalledPackage(p);}
        public void verifyStorageCompatibility(String p,String v,String d){delegate.verifyStorageCompatibility(p,v,d);}
        public void prepare(String p,String v,String d){delegate.prepare(p,v,d);}
        public boolean ready(String p,String v,String d){
            boolean ready=delegate.ready(p,v,d);
            if(ready&&mode.equals("crash"))try{
                durable(root.resolve("crashed-runner.json"),delegate.health(p));crash("READY");
            }catch(IOException e){throw new UncheckedIOException(e);}
            return ready;
        }
        public void publish(String tenant,Set<String> plugins,long generation){
            delegate.publish(tenant,plugins,generation);
            if(!plugins.isEmpty()&&mode.equals("crash"))try{
                check(delegate.snapshot(context(tenant)).enabledManifests().containsKey(PLUGIN),"real Engine catalog publication");
                durable(root.resolve("published.json"),Map.of("intentGeneration",generation,"engineGeneration",delegate.snapshot(context(tenant)).generation(),"plugins",plugins));
                crash("PUBLISHED");
            }catch(IOException e){throw new UncheckedIOException(e);}
        }
        public long activeCalls(String t,String p){return delegate.activeCalls(t,p);}
        public void cancel(String t,String p){delegate.cancel(t,p);}
        public void stop(String p){delegate.stop(p);}
    }
    static Map<String,Object> capability(){return Json.obj("id","echo","version","1.0.0","permission","echo","sideEffects",false,"idempotent",false,"timeoutMillis",3000,"inputSchema",Map.of("type","object"),"outputSchema",Map.of("type","object"));}
    static void fixture(Path directory,String language)throws IOException {
        Files.createDirectories(directory);TestJournals.initialize(directory);
        String entry=language.equals("python")?"runtime.py":"runtime.mjs";
        Map<String,Object> manifest=Json.obj("manifestVersion",1,"pluginId",PLUGIN,"version","1.0.0","publisher","crash-test","contractVersion","1.0","runtime",Json.obj("type","local","entry",entry,"argv",List.of()),"provides",List.of(capability()),"dependencies",List.of(),"requires",List.of());
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(directory.resolve("source.zip")))){
            for(var file:Map.of("plugin.json",Json.write(manifest).getBytes(StandardCharsets.UTF_8),entry,Files.readAllBytes(Path.of("tests/runtime-lifecycle/plugins/"+entry))).entrySet()){
                ZipEntry zipEntry=new ZipEntry(file.getKey());zipEntry.setTime(0);out.putNextEntry(zipEntry);out.write(file.getValue());out.closeEntry();
            }
        }
        durable(directory.resolve("fixture.json"),Map.of("language",language,"digest",digest(directory),"plugin",PLUGIN));
        durable(directory.resolve("business-data.json"),Map.of("student","synthetic-retained","del",0));
    }
    static String digest(Path directory)throws IOException{return VerifiedPackageRepository.hash(Files.readAllBytes(directory.resolve("source.zip")));}
    static Map<String,Object> state()throws IOException {
        return TestJournals.state(root);
    }
    static boolean installOnly(){return point.equals("STAGED")||point.equals("VERIFIED");}
    static String onlyOperation()throws IOException {
        Set<String> ids=Schema.map(state().get("operations")).keySet();check(ids.size()==1,"one operation, no duplicate replay");return ids.iterator().next();
    }
    static void closedRoute(EngineRuntimePort runtime,String tenant){
        check(runtime.snapshot(context(tenant)).enabledManifests().isEmpty(),"no route for "+tenant);
        try{runtime.invoke(context(tenant),PLUGIN,"echo",Map.of(),null,null);throw new AssertionError("unexpected callable route for "+tenant);}
        catch(FcpException e){check(Set.of("NOT_INSTALLED","CAPABILITY_MISSING","ADMISSION_CLOSED","PERMISSION_DENIED","SCOPE_MISSING").contains(e.code),"expected fail-closed code, got "+e.code);}
    }
    static void child(String[] args)throws Exception {
        mode=args[1];root=Path.of(args[2]);point=args[3];
        Runtime.getRuntime().addShutdownHook(new Thread(()->{try{durable(root.resolve(mode+"-shutdown-hook.json"),Map.of("executed",true));}catch(IOException e){throw new UncheckedIOException(e);}}));
        Host host=new Host(mode.equals("deny"));
        var repository=new VerifiedPackageRepository(root.resolve("inventory"),Set.of("crash-test"));
        if(mode.equals("crash")){
            // STAGED is stopped inside the unmodified PackageInstaller by the external JDI driver.
            var installed=repository.install(root.resolve("source.zip"),digest(root),"same-install-key");
            durable(root.resolve("verified.json"),Map.of("digest",installed.sha256(),"directory",installed.directory().toString()));crash("VERIFIED");
            try(var runtime=new EngineRuntimePort(host,repository,Map.of("python",args[4],"node",args[5]),TestJournals.open(root,"calls"),8);
                var coordinator=new LifecycleCoordinator(new CrashJournal(TestJournals.open(root,"control")),host,new CrashRuntime(runtime))){
                coordinator.register(context(TENANT),installed,coordinator.revision());
                var plan=coordinator.plan(context(TENANT),TENANT,PLUGIN,LifecycleCoordinator.Action.ENABLE,coordinator.revision());
                durable(root.resolve("confirmed-plan.json"),Map.of("tenant",plan.tenant(),"plugin",plan.plugin(),"action",plan.action().name(),"expectedRevision",plan.expectedRevision(),"closure",plan.closure(),"digest",plan.digest()));
                coordinator.confirm(context(TENANT),plan,Set.of(PLUGIN),"same-enable-key");
                throw new AssertionError("Requested crash boundary was not hit: "+point);
            }
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("mode",mode);result.put("point",point);result.put("jvmPid",ProcessHandle.current().pid());
        try(var runtime=new EngineRuntimePort(host,repository,Map.of("python",args[4],"node",args[5]),TestJournals.open(root,"calls"),8);
            var coordinator=new LifecycleCoordinator(TestJournals.open(root,"control"),host,runtime)){
            closedRoute(runtime,TENANT);closedRoute(runtime,"other-tenant");
            check(!coordinator.status(context(TENANT),TENANT,PLUGIN).effective().equals("READY"),"historical READY never trusted on JVM startup");
            result.put("startupFailClosed",true);
            if(mode.equals("deny")){
                if(installOnly())coordinator.reconcile(context(TENANT),TENANT);
                else {
                    try{coordinator.reconcile(context(TENANT),TENANT);throw new AssertionError("Revoked enable recovered a route");}
                    catch(FcpException e){check(e.code.equals("PERMISSION_DENIED"),"live revoke code");}
                }
                closedRoute(runtime,TENANT);check(runtime.health(PLUGIN).get("pid")==null,"revoked recovery never launches a process");
                result.put("revokedRecoveryDenied",true);
            }else {
                if(installOnly()&&mode.equals("recover")){
                    coordinator.reconcile(context(TENANT),TENANT);closedRoute(runtime,TENANT);
                    check(coordinator.installedMetadata(context(TENANT),PLUGIN).get("state").equals("ABSENT"),"unregistered staged/verified resources not auto-enabled");
                    var installed=repository.install(root.resolve("source.zip"),digest(root),"same-install-key");
                    var repeated=repository.install(root.resolve("source.zip"),digest(root),"same-install-key");
                    check(repeated.sha256().equals(installed.sha256())&&repeated.replayed(),"same-key verified install replay");
                    coordinator.register(context(TENANT),installed,coordinator.revision());
                    var plan=coordinator.plan(context(TENANT),TENANT,PLUGIN,LifecycleCoordinator.Action.ENABLE,coordinator.revision());
                    coordinator.confirm(context(TENANT),plan,Set.of(PLUGIN),"same-enable-key");
                }else coordinator.reconcile(context(TENANT),TENANT);
                String id=onlyOperation();var operation=coordinator.operation(context(TENANT),id);
                check(operation.phase().equals("COMPLETE")&&operation.outcome().equals("APPLIED"),"reconciled operation receipt");
                check(coordinator.status(context(TENANT),TENANT,PLUGIN).effective().equals("READY"),"actual recovered process ready");
                Map<String,Object> actual=Schema.map(runtime.invoke(context(TENANT),PLUGIN,"echo",Map.of("tenantId","other-tenant"),null,null));
                check(actual.get("tenant").equals(TENANT),"trusted tenant survives forged input");
                check(Schema.map(actual.get("principal")).get("id").equals("test-admin"),"trusted principal survives restart");
                Map<String,Object> identity=runtime.health(PLUGIN);result.put("runner",identity);
                if(Files.exists(root.resolve("crashed-runner.json"))){
                    Map<String,Object> old=read(root.resolve("crashed-runner.json"));
                    check(Schema.number(old.get("pid"))!=Schema.number(identity.get("pid"))||!Objects.equals(old.get("startedAt"),identity.get("startedAt")),"fresh identity, never adopt an unverified old PID");
                    result.put("freshIdentity",true);
                }
                long generation=runtime.snapshot(context(TENANT)).generation();coordinator.reconcile(context(TENANT),TENANT);
                check(generation==runtime.snapshot(context(TENANT)).generation(),"idempotent reconciliation keeps same live catalog generation");
                check(onlyOperation().equals(id),"operation receipt does not duplicate on recovery");
                if(Files.exists(root.resolve("confirmed-plan.json"))){
                    Map<String,Object> stored=read(root.resolve("confirmed-plan.json"));
                    var plan=new LifecycleCoordinator.Plan(TENANT,PLUGIN,LifecycleCoordinator.Action.ENABLE,Schema.number(stored.get("expectedRevision")),List.of(PLUGIN),Schema.str(stored.get("digest")));
                    var replay=coordinator.confirm(context(TENANT),plan,Set.of(PLUGIN),"same-enable-key");
                    check(replay.id().equals(id)&&replay.outcome().equals("APPLIED"),"receipt returned by original idempotency key");
                }
                closedRoute(runtime,"other-tenant");
                result.put("operationId",id);result.put("outcome",operation.outcome());result.put("idempotentGeneration",generation);result.put("tenantIsolation",true);
            }
        }
        check(Schema.number(ProcessProvider.metrics().get("activeProcesses"))==0,"all recovery processes reaped by actual runtime close");
        Map<String,Object> retained=read(root.resolve("business-data.json"));
        check(retained.get("student").equals("synthetic-retained")&&Schema.number(retained.get("del"))==0,"recovery does not delete business data sentinel");
        result.put("processCleanup",true);result.put("dataSentinelRetained",true);result.put("result","PASS");
        durable(root.resolve(mode+"-result.json"),result);System.out.println(Json.write(result));
    }
    static List<String> childArgs(String childMode,Path directory,String point,String python,String node){return List.of("child",childMode,directory.toString(),point,python,node);}
    static int launch(List<String> args,Path log)throws Exception {
        List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),CrashRecoveryTest.class.getName()));command.addAll(args);
        Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(45,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor();throw new AssertionError("child timeout, see "+log);}
        return process.exitValue();
    }
    static int stagedCrash(Path directory,String python,String node)throws Exception {
        List<String> source=Files.readAllLines(Path.of("core/src/main/java/fcp/plugin/PackageInstaller.java"));
        List<Integer> locations=new ArrayList<>();
        for(int i=0;i<source.size();i++)if(source.get(i).contains("if(!HexFormat.of().formatHex(md.digest()).equals(digest))"))locations.add(i+1);
        check(locations.size()==1,"installer staging boundary must resolve unambiguously");int line=locations.get(0);
        LaunchingConnector connector=Bootstrap.virtualMachineManager().defaultConnector();Map<String,Connector.Argument> arguments=connector.defaultArguments();
        arguments.get("main").setValue(CrashRecoveryTest.class.getName()+" "+String.join(" ",childArgs("crash",directory,"STAGED",python,node).stream().map(CrashRecoveryTest::quoted).toList()));
        arguments.get("options").setValue("-cp "+quoted(System.getProperty("java.class.path")));arguments.get("suspend").setValue("true");
        VirtualMachine vm=connector.launch(arguments);Process process=vm.process();
        Thread stdout=capture(process.getInputStream(),directory.resolve("crash.log")),stderr=capture(process.getErrorStream(),directory.resolve("crash-stderr.log"));
        boolean hit=false;
        try {
            ClassPrepareRequest prepared=vm.eventRequestManager().createClassPrepareRequest();prepared.addClassFilter("fcp.plugin.PackageInstaller");prepared.enable();vm.resume();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(!hit&&System.nanoTime()<deadline){
                EventSet events=vm.eventQueue().remove(500);if(events==null)continue;
                for(Event event:events){
                    if(event instanceof ClassPrepareEvent loaded){
                        List<Location> stops=loaded.referenceType().locationsOfLine(line);check(!stops.isEmpty(),"javac -g line table required");
                        BreakpointRequest breakpoint=vm.eventRequestManager().createBreakpointRequest(stops.get(0));breakpoint.setSuspendPolicy(EventRequest.SUSPEND_ALL);breakpoint.enable();
                    }else if(event instanceof BreakpointEvent){
                        Path staging=directory.resolve("inventory/installed/staging");
                        List<Path> archives;try(var paths=Files.walk(staging)){archives=paths.filter(p->p.getFileName().toString().equals("archive.zip")).toList();}
                        check(archives.size()==1,"one real PackageInstaller staged archive");
                        check(VerifiedPackageRepository.hash(Files.readAllBytes(archives.get(0))).equals(digest(directory)),"staged complete bytes before verification");
                        check(!Files.exists(directory.resolve("inventory/installed/packages").resolve(digest(directory))),"staged bytes not yet executable publication");
                        durable(directory.resolve("crash-marker.json"),Map.of("point","STAGED","mechanism","JDI VirtualMachine.exit (abrupt; no shutdown hooks)","jvmPid",process.pid(),"exitCode",CRASH_EXIT,"source","core/src/main/java/fcp/plugin/PackageInstaller.java","line",line,"stagedArchive",directory.relativize(archives.get(0)).toString()));
                        hit=true;vm.exit(CRASH_EXIT);
                    }else if(event instanceof VMDeathEvent||event instanceof VMDisconnectEvent){throw new AssertionError("target exited before staging breakpoint");}
                }
                if(!hit)events.resume();
            }
            check(hit,"staging breakpoint timeout");check(process.waitFor(10,TimeUnit.SECONDS),"debugged JVM must really terminate");return process.exitValue();
        }finally{
            if(process.isAlive()){process.destroyForcibly();process.waitFor();}stdout.join(2000);stderr.join(2000);
        }
    }
    static String quoted(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    static Thread capture(InputStream input,Path log){Thread t=new Thread(()->{try(input;OutputStream out=Files.newOutputStream(log)){input.transferTo(out);}catch(IOException e){throw new UncheckedIOException(e);}});t.setDaemon(true);t.start();return t;}
    static Map<String,Object> checkpoint(Path directory,String point)throws IOException {
        Map<String,Object> state=TestJournals.state(directory);
        durable(directory.resolve("crash-journals.json"),TestJournals.inspect(directory));
        if(point.equals("STAGED")||point.equals("VERIFIED")){check(state.isEmpty(),"before registration no durable enabled intent");}
        else {
            Map<String,Object> operations=Schema.map(state.get("operations"));check(operations.size()==1,"exactly one persisted operation at crash");
            Map<String,Object> op=Schema.map(operations.values().iterator().next());String expected=point.equals("INTENT")?"INTENT":point.equals("RECEIPT")?"COMPLETE":"PREPARING";
            check(op.get("phase").equals(expected),"crash at "+point+" must persist "+expected+", got "+op.get("phase"));
            if(point.equals("RECEIPT"))check(op.get("outcome").equals("APPLIED"),"receipt actually persisted");
            else check(!op.get("outcome").equals("APPLIED"),"no prematurely completed receipt");
        }
        if(point.equals("READY"))check(!Files.exists(directory.resolve("published.json")),"ready crash precedes publication");
        if(point.equals("PUBLISHED")||point.equals("RECEIPT"))check(Files.exists(directory.resolve("published.json")),"publication must precede crash");
        durable(directory.resolve("crash-state.json"),state);return state;
    }
    static Path revocationFork(Path directory)throws IOException {
        // Preserve the original post-crash state for direct recovery; denial mutates only this copy.
        List<Path> paths;try(var stream=Files.walk(directory)){paths=stream.toList();}
        Path fork=Files.createDirectory(directory.resolve("revoked"));
        for(Path source:paths){if(source.equals(directory))continue;Path target=fork.resolve(directory.relativize(source));
            check(!Files.isSymbolicLink(source),"test artifacts must not contain links");
            if(Files.isDirectory(source))Files.createDirectory(target);else Files.copy(source,target);
        }
        TestJournals.fork(directory,fork);return fork;
    }
    static void run(String[] args)throws Exception {
        Path output=Path.of(args[1]).toAbsolutePath();Files.createDirectories(output);List<Object> cases=new ArrayList<>();int failures=0;
        Map<String,Object> databasePreflight=TestJournals.preflight();durable(output.resolve("journal-preflight.json"),databasePreflight);
        for(String language:List.of("python","node"))for(String point:POINTS){
            Path directory=output.resolve(language+"-"+point.toLowerCase(Locale.ROOT));Map<String,Object> result=new LinkedHashMap<>();result.put("language",language);result.put("point",point);result.put("evidence",directory.getFileName().toString());
            try {
                check(!Files.exists(directory),"fresh case directory required: "+directory);fixture(directory,language);
                int exit=point.equals("STAGED")?stagedCrash(directory,args[2],args[3]):launch(childArgs("crash",directory,point,args[2],args[3]),directory.resolve("crash.log"));
                result.put("crashExitCode",exit);check(exit==CRASH_EXIT,"real JVM crash exit must be "+CRASH_EXIT+", see "+directory.resolve("crash.log"));
                check(Files.exists(directory.resolve("crash-marker.json")),"exact crash boundary marker");check(!Files.exists(directory.resolve("crash-shutdown-hook.json")),"abrupt termination must skip shutdown hooks");checkpoint(directory,point);
                Path revoked=revocationFork(directory);
                for(String childMode:List.of("deny","recover","verify")){
                    Path target=childMode.equals("deny")?revoked:directory;
                    Thread.sleep(300);int recovered=launch(childArgs(childMode,target,point,args[2],args[3]),target.resolve(childMode+".log"));
                    result.put(childMode+"ExitCode",recovered);check(recovered==0,childMode+" failed; see "+target.resolve(childMode+".log"));check(read(target.resolve(childMode+"-result.json")).get("result").equals("PASS"),"recovery assertions completed");
                    check(Files.exists(target.resolve(childMode+"-shutdown-hook.json")),"shutdown-hook control must execute on normal exit");
                }
                result.put("revokedRecoveryUsesIndependentCrashStateCopy",true);
                durable(directory.resolve("final-journals.json"),TestJournals.inspect(directory));
                // Partial staging is deliberately retained, never guessed safe for destructive cleanup.
                if(point.equals("STAGED")){String staged=Schema.str(read(directory.resolve("crash-marker.json")).get("stagedArchive"));check(Files.exists(directory.resolve(staged)),"orphan staging retained until an explicit retention policy");result.put("orphanStagingRetained",true);}
                result.put("result","PASS");System.out.println("PASS "+language+" "+point+": abrupt exit, denied recovery, allowed recovery, second JVM restart");
            }catch(Exception|AssertionError failure){failures++;result.put("result","FAIL");result.put("failure",failure.toString());failure.printStackTrace(System.out);}
            cases.add(result);
        }
        Map<String,Object> report=Json.obj("result",failures==0?"PASS":"FAIL","completedAt",Instant.now().toString(),"crashCases",cases.size(),"failedCases",failures,"journal",TestJournals.mysql?"JdbcStateJournal (real dedicated MySQL commits)":"FileStateJournal (real fsync/atomic rename, not MySQL)","journalBackend",TestJournals.mysql?"MYSQL":"FILE","databasePreflight",databasePreflight,"mysqlJournalCrashRecovery",TestJournals.mysql?(failures==0?"PASS":"FAIL"):"NOT_RUN","productionClassesModifiedForInjection",false,"boundaries",POINTS,"languages",List.of("python","node"),"nativeRuoYiHttpAcceptance","NOT_RUN","schemaDdlCrashRecovery","NOT_RUN","mysqlServerCrashRecovery","NOT_RUN","powerFailureDurability","NOT_RUN","multiNodeRecovery","NOT_RUN","cases",cases);
        durable(output.resolve("acceptance.json"),report);System.out.println(Json.write(report));if(failures>0)throw new AssertionError(failures+" crash cases failed; original logs retained");
    }
    public static void main(String[] args)throws Exception {
        try{TestJournals.configure();if(args[0].equals("child"))child(args);else run(args);}
        catch(IOException blocked){
            if(!args[0].equals("child"))durable(Path.of(args[1]).resolve("acceptance.json"),Map.of("result","BLOCKED","mysqlJournalCrashRecovery","NOT_RUN","reason",blocked.getMessage()));
            throw blocked;
        }
    }
}
