import dev.fcp.dual.FileRecordStore;
import dev.fcp.host.RuntimeBundle;
import dev.fcp.host.UiRoutePolicy;
import dev.fcp.school.SchoolFacade;
import fcp.*;
import java.io.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static fcp.Schema.*;

/** Pure-JDK component tests. Explicitly NOT Spring/MySQL/native-Vue integration tests. */
public final class NativeBridgeRegression {
    private static int checks;
    static void ok(boolean result,String name) { if(!result)throw new AssertionError(name);checks++;System.out.println("PASS "+name); }
    static void denied(String code,Runnable action,String name){
        try{action.run();throw new AssertionError("Expected rejection: "+name);}
        catch(FcpException e){ok(e.code.equals(code),name+" -> "+e.code);}
    }
    static SchoolFacade.Identity identity(String scope,String who,boolean admin,boolean write){
        Set<String> p=new HashSet<>(List.of("fcp:eval:list","fcp:eval:get","fcp:eval:history","fcp:eval:analyze"));
        if(write)p.add("fcp:eval:create");
        if(admin)p.addAll(List.of("fcp:ui:admin","fcp:eval:manage","fcp:eval:allClasses"));
        return new SchoolFacade.Identity(scope,who,p,Set.of("class-a"));
    }
    static String envelope(Map<String,Object> snapshot,Map<String,Object> input){
        return Json.write(Json.obj("input",input,"generation",snapshot.get("generation"),"instance",snapshot.get("instance"),"expectedCaller",snapshot.get("caller")));
    }
    static void badBundle(Path resources,String index,String name)throws Exception{
        Files.writeString(resources.resolve("fcp/runtime/bundle.sha256"),index);
        try(var loader=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},null)){
            try(var ignored=RuntimeBundle.extract(loader)){throw new AssertionError("Bundle was accepted: "+name);}
            catch(IOException expected){ok(true,name);}
        }
    }
    public static void main(String[] args)throws Exception{
        Path source=Path.of(args[0]),temp=Files.createTempDirectory("fcp-native-test-");
        try{
            Path resources=temp.resolve("resources"),runtime=resources.resolve("fcp/runtime");Files.createDirectories(runtime);
            List<String> index=new ArrayList<>();
            try(var files=Files.walk(source)){
                for(Path file:files.filter(Files::isRegularFile).sorted().toList()){
                    String name=source.relativize(file).toString().replace('\\','/');byte[] bytes=Files.readAllBytes(file);
                    Path dst=runtime.resolve(name);Files.createDirectories(dst.getParent());Files.write(dst,bytes);
                    index.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))+"  "+name);
                }
            }
            String goodIndex=String.join("\n",index)+"\n";Files.writeString(runtime.resolve("bundle.sha256"),goodIndex);
            var admin=identity("tenant-000000","100",true,true);
            var teacher=identity("tenant-000000","101",false,true);
            var viewer=identity("tenant-000000","102",false,false);
            var other=identity("tenant-000001","201",false,true);
            ok(UiRoutePolicy.session(admin).get("defaultShell").equals("admin"),"admin default shell");
            ok(UiRoutePolicy.session(teacher).get("allowedShells").equals(List.of("teacher")),"teacher cannot select admin shell");
            ok(UiRoutePolicy.session(viewer).get("defaultShell").equals("teacher"),"viewer teacher shell");
            var releaseAdmin=new SchoolFacade.Identity("tenant-000000","1",Set.of("fcp:release:view","fcp:release:manage"),Set.of());
            var releaseViewer=new SchoolFacade.Identity("tenant-000000","101",Set.of("fcp:release:view"),Set.of());
            var releaseForeign=new SchoolFacade.Identity("tenant-000001","201",Set.of("fcp:release:view","fcp:release:manage"),Set.of());
            var workspaceForeign=new SchoolFacade.Identity("tenant-000001","201",Set.of("fcp:plugin:view","fcp:release:view","fcp:release:manage"),Set.of());
            var wildcard=new SchoolFacade.Identity("tenant-000000","1",Set.of("fcp:eval:list","*:*:*"),Set.of());
            ok(UiRoutePolicy.session(releaseAdmin).get("releasePermissions").equals(List.of("fcp:release:view","fcp:release:manage")),"explicit native release grants projected without wildcard");
            ok(UiRoutePolicy.session(releaseViewer).get("releasePermissions").equals(List.of("fcp:release:view")),"release viewer projection cannot become manager");
            denied("FORBIDDEN",()->UiRoutePolicy.session(releaseForeign),"non-platform release-only menus do not grant workspace access");
            denied("FORBIDDEN",()->UiRoutePolicy.soybeanRoutes(releaseForeign),"non-platform release-only menus do not grant teacher routes");
            ok(UiRoutePolicy.session(workspaceForeign).get("releasePermissions").equals(List.of()),"authorized non-platform workspace projects no release permissions despite menu grants");
            var foreignRoutes=UiRoutePolicy.soybeanRoutes(workspaceForeign).get(0);
            ok(arr(foreignRoutes,"children").stream().map(Schema::map).noneMatch(childRoute->"releases".equals(childRoute.get("path"))),"authorized non-platform workspace has no release route");
            ok(UiRoutePolicy.session(workspaceForeign).get("home").equals("/teacher/fcp/plugins"),"non-platform workspace keeps its authorized plugin home");
            ok(UiRoutePolicy.session(wildcard).get("releasePermissions").equals(List.of()),"native wildcard does not create FCP release permission");
            var noGrant=new SchoolFacade.Identity("tenant-000000","103",Set.of(),Set.of());
            denied("FORBIDDEN",()->UiRoutePolicy.session(noGrant),"UI session requires host grant");
            var route=UiRoutePolicy.soybeanRoutes(teacher).get(0);
            ok(route.get("component").equals("Layout"),"Soybean consumes raw RuoYi layout");
            var child=map(arr(route,"children").get(0));
            ok(child.get("path").equals("school"),"child path is relative for native parser");
            ok(child.get("component").equals("fcp/school/index"),"native view path matches source");
            Path extracted;
            try(var loader=new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},null);
                var bundle=RuntimeBundle.extract(loader)){
                extracted=bundle.root();ok(Files.isRegularFile(extracted.resolve("plugins/statistics.py")),"classpath Python resource extracted");
                try(var facade=new SchoolFacade(extracted,new FileRecordStore(temp.resolve("unit-test-store.json")),null)){
                    var a=facade.describe(admin);var t=facade.describe(teacher);var v=facade.describe(viewer);var o=facade.describe(other);
                    ok(((Number)facade.metrics(admin).get("activeProcesses")).intValue()==0,"no Python process before call");
                    ok(!arr(v,"operations").contains("create"),"viewer has no create contribution");
                    var data=Json.obj("student","unit-student","classId","class-a","indicator","课堂参与","score",86,"comment","unit evidence");
                    var write=Json.obj("data",data,"requestKey","native-test-request-001");
                    var row=map(facade.invoke(teacher,"create",envelope(t,write)));
                    ok(row.containsKey("id"),"teacher creates authorized row using test store");
                    var again=map(facade.invoke(teacher,"create",envelope(t,write)));
                    ok(again.get("id").equals(row.get("id")),"idempotent replay retains record ID");
                    denied("FORBIDDEN",()->facade.invoke(viewer,"create",envelope(v,write)),"viewer write rejected in core");
                    var forbidden=new LinkedHashMap<>(data);forbidden.put("classId","class-b");
                    denied("FORBIDDEN",()->facade.invoke(teacher,"create",envelope(t,Json.obj("data",forbidden,"requestKey","native-test-request-002"))),"class boundary enforced");
                    denied("NOT_FOUND",()->facade.invoke(other,"get",envelope(o,Json.obj("id",row.get("id")))),"cross-tenant lookup rejected");
                    denied("IDENTITY_CHANGED",()->facade.invoke(other,"list",envelope(t,Json.obj())),"caller precondition cannot switch tenant");
                    var stats=map(facade.invoke(teacher,"analyze",envelope(t,Json.obj())));
                    ok(stats.get("engine").equals("python-stdlib"),"actual Python stdio provider called");
                    ok(number(stats.get("count"))==1 && ((Number)stats.get("average")).doubleValue()==86,"Python output matches input");
                    long pid=number(stats.get("pid"));ok(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"Python PID is alive after call");
                    long deadline=System.nanoTime()+7_000_000_000L;
                    while(System.nanoTime()<deadline && ((Number)facade.metrics(admin).get("activeProcesses")).intValue()!=0)Thread.sleep(50);
                    ok(((Number)facade.metrics(admin).get("activeProcesses")).intValue()==0,"Python idle reclamation");
                    ok(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"reclaimed child exited");
                    facade.compose(admin,envelope(a,Json.obj("enabled",false)));
                    denied("STALE_COMPOSITION",()->facade.invoke(teacher,"list",envelope(t,Json.obj())),"stale generation rejected");
                    var disabled=facade.describe(teacher);
                    ok(!arr(disabled,"operations").contains("analyze"),"disabled Python hidden from operation catalog");
                    denied("PLUGIN_DISABLED",()->facade.invoke(teacher,"analyze",envelope(disabled,Json.obj())),"disabled Python direct call rejected");
                    var list=map(facade.invoke(teacher,"list",envelope(disabled,Json.obj())));
                    ok(arr(list,"items").size()==1,"disabling Python preserves data");
                    facade.compose(admin,envelope(facade.describe(admin),Json.obj("enabled",true)));
                    var resumed=map(facade.invoke(teacher,"analyze",envelope(facade.describe(teacher),Json.obj())));
                    ok(number(resumed.get("count"))==1,"reenabled Python retains data semantics");
                }
                ok(((Number)ProcessProvider.metrics().get("activeProcesses")).intValue()==0,"host facade closes all child processes");
            }
            ok(!Files.exists(extracted),"runtime extraction cleaned after shutdown");
            badBundle(resources,goodIndex.replace(index.get(0).substring(0,64),"0".repeat(64)),"checksum tampering rejected");
            badBundle(resources,"0".repeat(64)+"  ../outside.py\n","path traversal rejected");
            badBundle(resources,goodIndex+index.get(0)+"\n","duplicate runtime path rejected");
            badBundle(resources,"invalid-index\n","malformed runtime index rejected");
            badBundle(resources,"","empty runtime bundle rejected");
            System.out.println("RESULT "+checks+" component checks PASS; Spring/MySQL/Vue builds NOT RUN");
        }finally{
            try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
}
