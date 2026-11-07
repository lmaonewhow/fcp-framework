import dev.fcp.host.ui.*;
import fcp.*;
import fcp.control.*;
import fcp.host.*;
import fcp.release.routing.*;
import fcp.release.version.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.net.*;
import java.net.http.*;
import com.sun.net.httpserver.HttpServer;

public class UiReleaseAssetsTest {
    static int checks;
    static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    interface Checked {void run()throws Exception;}
    static void denied(Checked call,String name){try{call.run();throw new AssertionError(name);}catch(AssertionError e){throw e;}catch(Exception expected){check(true,name);}}
    static TrustedContext context(String tenant){return new TrustedContext("request","trace",tenant,new TrustedContext.Principal(TrustedContext.Kind.USER,"user"),1,Set.of(),Instant.now().plusSeconds(300));}
    static String bundle(Path root,String shell,String version)throws Exception {
        String html="<!doctype html><html><head><script type=\"module\" src=\"./assets/app.js\"></script></head><body>"+shell+version+"</body></html>";
        var files=Map.of("index.html",html,"assets/app.js","document.body.dataset.version='"+version+"';");
        Map<String,Object> inventory=new TreeMap<>();for(var e:files.entrySet()){byte[] b=e.getValue().getBytes(StandardCharsets.UTF_8);inventory.put(e.getKey(),Map.of("sha256",VersionCodec.sha256(b),"sizeBytes",b.length));}
        Map<String,Object> manifest=new TreeMap<>(Map.of("format",1,"shell",shell,"backendContract","fcp.host/1","files",inventory));String digest=VersionCodec.sha256(VersionCodec.canonical(manifest));manifest.put("digest",digest);
        Path dir=root.resolve(digest).resolve(shell);Files.createDirectories(dir.resolve("assets"));for(var e:files.entrySet())Files.writeString(dir.resolve(e.getKey()),e.getValue());Files.writeString(dir.resolve("manifest.json"),VersionCodec.canonical(manifest));return digest;
    }
    public static void main(String[] args)throws Exception {
        Path tmp=Files.createTempDirectory("fcp-ui-assets");Path bundles=tmp.resolve("bundles");Files.createDirectories(bundles);
        UiBundleStore store=new UiBundleStore(bundles);String oldAdmin=bundle(bundles,"admin","v1"),newAdmin=bundle(bundles,"admin","v2"),oldTeacher=bundle(bundles,"teacher","v1"),newTeacher=bundle(bundles,"teacher","v2");
        try(var policy=new FileStateJournal(tmp.resolve("policy"));var registry=new FileStateJournal(tmp.resolve("versions"))) {
            var router=new DurableReleaseRouter(policy,new byte[32],Clock.systemUTC(),Duration.ofMinutes(5));var versions=new JournalVersionProvider("test",registry);
            for(var entry:Map.of("a1",oldAdmin,"a2",newAdmin,"t1",oldTeacher,"t2",newTeacher).entrySet()){
                String shell=entry.getKey().startsWith("a")?"admin":"teacher";var b=store.bundle(entry.getValue(),shell);
                versions.registerArtifact(new SystemArtifact(entry.getKey(),shell.equals("admin")?SystemArtifact.Kind.ADMIN_UI:SystemArtifact.Kind.TEACHER_UI,shell,entry.getKey(),entry.getValue(),b.sizeBytes(),"bundle:"+entry.getValue(),Map.of(),Map.of("format","fcp.ui-bundle/1")));
            }
            versions.registerRelease(new SystemRelease("r1","1",Map.of("admin-ui","a1","teacher-ui","t1"),Map.of("hostContract","fcp.host/1"),Map.of()));
            versions.registerRelease(new SystemRelease("r2","2",Map.of("admin-ui","a2","teacher-ui","t2"),Map.of("hostContract","fcp.host/1"),Map.of()));
            try {new UiReleaseApplication(router,versions,store,"fcp.host/1",(c,shell)->{}).bootstrap(context("a"),"admin",null);throw new AssertionError("empty policy");}
            catch(FcpException e){check(e.code.equals("UI_RELEASE_NOT_CONFIGURED"),"first deployment explicit embedded fallback only");}
            router.put(new ReleasePolicy("ui","r1","r2",0,Set.of("a"),Set.of(),true,0),0);
            var app=new UiReleaseApplication(router,versions,store,"fcp.host/1",(c,s)->{if(c.tenantId().equals("denied"))throw new FcpException("PERMISSION_DENIED","denied");});
            var a=app.bootstrap(context("a"),"admin",null);var b=app.bootstrap(context("b"),"admin",null);
            check(a.get("releaseId").equals("r2")&&a.get("bundleDigest").equals(newAdmin),"candidate tenant actual admin bundle");
            check(b.get("releaseId").equals("r1")&&b.get("bundleDigest").equals(oldAdmin),"stable tenant actual admin bundle");
            check(app.bootstrap(context("a"),"teacher",(String)a.get("affinity")).get("bundleDigest").equals(newTeacher),"same affinity selects matching teacher build");
            denied(()->app.bootstrap(context("b"),"admin",(String)a.get("affinity")),"cross tenant affinity rejected");
            denied(()->app.bootstrap(context("a"),"admin","forged"),"forged release selector rejected");
            denied(()->app.bootstrap(context("denied"),"admin",null),"shell permission not granted by rollout");
            String html=new String(store.index(store.bundle(newAdmin,"admin")).bytes(),StandardCharsets.UTF_8);
            check(html.contains("<base href=\"/fcp/ui/assets/"+newAdmin+"/admin/\">"),"index has immutable version base");
            check(VersionCodec.sha256(html).equals(a.get("indexDigest")),"personal bootstrap binds exact public index bytes");
            denied(()->store.asset(store.bundle(newAdmin,"admin"),"../manifest.json"),"asset traversal rejected");
            denied(()->store.asset(store.bundle(newAdmin,"admin"),"assets%2fapp.js"),"encoded separator rejected");
            denied(()->store.bundle(newAdmin,"teacher"),"shell and digest identity cannot mix");
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{try{String[] p=exchange.getRequestURI().getPath().split("/",7);var selected=store.bundle(p[4],p[5]);var asset=p[6].equals("index.html")?store.index(selected):store.asset(selected,p[6]);exchange.getResponseHeaders().add("Cache-Control","public, max-age=31536000, immutable");exchange.getResponseHeaders().add("Content-Type",asset.contentType());exchange.sendResponseHeaders(200,asset.bytes().length);exchange.getResponseBody().write(asset.bytes());}catch(Exception e){exchange.sendResponseHeaders(404,-1);}finally{exchange.close();}});server.start();
            try{var client=HttpClient.newHttpClient();for(String digest:List.of(oldAdmin,newAdmin)){String prefix="http://127.0.0.1:"+server.getAddress().getPort()+"/fcp/ui/assets/"+digest+"/admin/";var response=client.send(HttpRequest.newBuilder(URI.create(prefix+"index.html")).build(),HttpResponse.BodyHandlers.ofString());check(response.statusCode()==200&&response.body().contains(digest),"actual HTTP serves matching immutable index");check(response.headers().firstValue("Cache-Control").orElse("").contains("immutable"),"only static asset response immutable");var js=client.send(HttpRequest.newBuilder(URI.create(prefix+"assets/app.js")).build(),HttpResponse.BodyHandlers.ofString());check(js.body().contains(digest.equals(oldAdmin)?"v1":"v2"),"actual HTTP serves correct build JS");}}finally{server.stop(0);}
            Path liveJs=bundles.resolve(newAdmin).resolve("admin/assets/app.js");byte[] retained=Files.readAllBytes(liveJs);Files.delete(liveJs);
            try{app.bootstrap(context("a"),"admin",null);throw new AssertionError("missing active asset");}catch(FcpException e){check(e.code.equals("UI_ASSET_UNVERIFIED"),"missing active asset fails closed not embedded fallback");}finally{Files.write(liveJs,retained);}
            router.put(new ReleasePolicy("ui","r1","r2",0,Set.of(),Set.of(),false,1),router.revision());denied(()->app.bootstrap(context("a"),"admin",(String)a.get("affinity")),"emergency stop invalidates bootstrap");
            check(store.asset(store.bundle(oldAdmin,"admin"),"assets/app.js").bytes().length>0,"old static assets retained after stop without business authority");
            Files.writeString(bundles.resolve(newAdmin).resolve("admin/assets/app.js"),"tampered");denied(()->store.asset(store.bundle(newAdmin,"admin"),"assets/app.js"),"tampered bytes not served");
            router.put(new ReleasePolicy("ui","r1","r2",0,Set.of("a"),Set.of(),true,2),router.revision());denied(()->app.bootstrap(context("a"),"admin",null),"incomplete corrupt bundle cannot become bootstrap");
            System.out.println("PASS "+checks+" UI asset assertions; genuine RuoYi/MySQL/Vue browser NOT_RUN");
        }
    }
}
