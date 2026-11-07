package dev.fcp.dev;

import com.sun.net.httpserver.*;
import dev.fcp.host.*;
import fcp.*;
import fcp.control.*;
import fcp.host.*;
import fcp.runtime.*;
import fcp.storage.*;
import fcp.exchange.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static fcp.Schema.*;

/** Explicit loopback-only development host. Never accepted as native RuoYi authentication evidence. */
public final class DevHost implements AutoCloseable {
    private final HttpServer server;private final ExecutorService executor;
    private final EngineRuntimePort runtime;private final LifecycleCoordinator lifecycle;
    private final ExchangeService exchange;
    private final DevelopmentSettings settings;
    private final PluginApplication application;private final Authority authority;private final PluginRecovery recovery;
    public DevHost(Path root,Path privateConfig,int port)throws Exception {
        if("production".equalsIgnoreCase(System.getenv("FCP_ENV")))throw new IllegalArgumentException("Development host forbidden in production");
        if(port<0||port>65535)throw new IllegalArgumentException("Port out of range");
        if(Files.isSymbolicLink(privateConfig)||Files.size(privateConfig)>1024*1024)throw new IOException("Invalid development identity file");
        Map<String,Object> config=map(Json.parse(Files.readString(privateConfig)));
        if(!Boolean.TRUE.equals(config.get("development")))throw new IllegalArgumentException("Development configuration required");
        authority=new Authority(privateConfig,root);
        Set<String> publishers=new HashSet<>();for(Object v:arr(config,"trustedPublishers"))publishers.add(str(v));
        Map<String,String> executables=new HashMap<>();for(var e:obj(config,"executables").entrySet()){String path=str(e.getValue());if(!Path.of(path).isAbsolute())throw new IllegalArgumentException("Absolute runtime paths required");executables.put(e.getKey(),path);}
        var repository=new VerifiedPackageRepository(root.resolve("packages"),publishers);
        runtime=new EngineRuntimePort(authority,repository,executables,root.resolve("calls"),3);
        if(config.containsKey("storageJdbc")) {
            var jdbc=obj(config,"storageJdbc");
            var source=new DevelopmentDataSource(str(jdbc.get("url")),str(jdbc.get("user")),str(jdbc.get("password")));
            var compatibility=new JdbcStorageCompatibility(source);
            runtime.setStorageCompatibilityVerifier(compatibility::verify);
            runtime.setStorageCompatibilityFingerprint(compatibility::fingerprint);
            var storage=new StorageCapabilities(new JdbcPluginStorage(source,authority));
            for(var contract:StorageCapabilities.contracts()) {
                String operation=contract.id().substring("host.storage.".length());
                runtime.registerHostCapability(contract,(plugin,context,capability,input)->storage.invoke(plugin,context,operation,input));
            }
        }
        exchange=createExchange(config,root);
        settings=new DevelopmentSettings(root.resolve("settings"));
        lifecycle=new LifecycleCoordinator(root.resolve("control"),authority,runtime);
        lifecycle.setEnablementPolicy(authority::verifyEnablement);
        application=new PluginApplication(authority,repository,lifecycle,runtime,root.resolve("uploads"),authority::permissions);
        server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),port),32);
        executor=new ThreadPoolExecutor(2,6,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor);server.createContext("/fcp/",this::handle);server.createContext("/dev/",this::staticResource);
        recovery=new PluginRecovery(lifecycle,authority::recoveryContext);
    }
    public void start(){server.start();}
    public int port(){return server.getAddress().getPort();}
    private void handle(HttpExchange x)throws IOException {
        try {
            TrustedContext context=authority.authenticate(x.getRequestHeaders().getFirst("Authorization"));
            String path=x.getRequestURI().getRawPath();String method=x.getRequestMethod();Object result;
            if(path.contains("%")||path.contains(".."))throw new FcpException("INVALID_ARGUMENT","Invalid API path");
            if(path.equals("/fcp/development/configuration")) {
                if(!authority.permissions(context).contains(method.equals("GET")?"fcp:plugin:view":"fcp:plugin:manage"))throw new FcpException("PERMISSION_DENIED","Development configuration permission required");
                if(method.equals("GET"))result=settings.get(context.tenantId());
                else if(method.equals("POST")){var body=readBody(x);fields(body,Set.of("expectedRevision","values"));result=settings.put(context.tenantId(),number(body.get("expectedRevision")),map(body.get("values")));}
                else throw new FcpException("NOT_FOUND","Unknown configuration endpoint");
            } else if(path.startsWith("/fcp/exchange/")) {
                if(exchange==null)throw new FcpException("NOT_FOUND","Exchange fixtures are not enabled");
                if(!authority.permissions(context).contains("fcp:plugin:view"))throw new FcpException("PERMISSION_DENIED","Plugin view required");
                String[] parts=path.substring("/fcp/exchange/".length()).split("/");
                if(method.equals("GET")&&parts.length==2&&parts[0].equals("downloads")) {
                    byte[] data=exchange.download(context,parts[1]);
                    x.getResponseHeaders().set("Content-Type","text/csv; charset=utf-8");
                    x.getResponseHeaders().set("Cache-Control","no-store");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
                    x.getResponseHeaders().set("Content-Disposition","attachment; filename=\"fcp-fixture-export.csv\"");
                    x.sendResponseHeaders(200,data.length);x.getResponseBody().write(data);return;
                }
                if(!method.equals("POST")||parts.length!=3)throw new FcpException("NOT_FOUND","Unknown exchange endpoint");
                if(parts[0].equals("configs")&&parts[2].equals("preview")) result=exchange.preview(context,parts[1],"uploaded-csv",x.getRequestBody());
                else {
                    var body=readBody(x);
                    if(parts[0].equals("configs")&&parts[2].equals("preview-api")){fields(body,Set.of("cursor"));result=exchange.previewConnector(context,parts[1],body.containsKey("cursor")?str(body.get("cursor")):"");}
                    else if(parts[0].equals("previews")&&parts[2].equals("confirm")){fields(body,Set.of("digest","idempotencyKey"));result=exchange.confirm(context,parts[1],str(body.get("digest")),str(body.get("idempotencyKey")));}
                    else if(parts[0].equals("configs")&&parts[2].equals("exports")){fields(body,Set.of("fields"));Set<String> selected=new LinkedHashSet<>();for(Object field:arr(body,"fields"))if(!selected.add(str(field)))throw new FcpException("INVALID_ARGUMENT","Duplicate field");result=exchange.export(context,parts[1],selected);}
                    else throw new FcpException("NOT_FOUND","Unknown exchange endpoint");
                }
            } else if(method.equals("GET")&&path.equals("/fcp/session")) result=Json.obj("host","development","tenantId",context.tenantId(),"userId",context.principal().id(),"permissions",authority.permissions(context),"scopes",context.dataScopes(),"exchangeEnabled",exchange!=null);
            else if(method.equals("POST")&&path.equals("/fcp/packages/staged")) {
                result=application.stage(context,x.getRequestBody(),header(x,"X-Fcp-Digest"),header(x,"Idempotency-Key"));
            } else if(method.equals("POST")&&path.equals("/fcp/packages")) {
                result=application.install(context,x.getRequestBody(),header(x,"X-Fcp-Digest"),header(x,"Idempotency-Key"),Long.parseLong(header(x,"X-Fcp-Revision")));
            } else if(method.equals("GET")&&path.equals("/fcp/plugins"))result=application.list(context);
            else if(method.equals("GET")&&path.equals("/fcp/ui/catalog"))result=application.catalog(context);
            else {
                String[] p=path.substring(5).split("/");
                Map<String,Object> body=method.equals("POST")?readBody(x):Map.of();
                if(p.length==2&&p[0].equals("plugins")&&method.equals("GET"))result=application.status(context,p[1]);
                else if(p.length==3&&p[0].equals("plugins")&&p[2].equals("plans")&&method.equals("POST"))result=application.plan(context,p[1],str(body.get("action")),number(body.get("expectedRevision")));
                else if(p.length==3&&p[0].equals("plans")&&p[2].equals("confirm")&&method.equals("POST")){Set<String> affected=new HashSet<>();for(Object item:arr(body,"affectedPlugins"))affected.add(str(item));result=application.confirm(context,p[1],str(body.get("digest")),affected,str(body.get("idempotencyKey")));}
                else if(p.length==2&&p[0].equals("operations")&&method.equals("GET"))result=application.operation(context,p[1]);
                else if(p.length==3&&p[0].equals("operations")&&p[2].equals("decision")&&method.equals("POST"))result=application.decide(context,p[1],str(body.get("decision")));
                else if(p.length==3&&p[0].equals("capabilities")&&p[2].equals("invoke")&&method.equals("POST"))result=application.invoke(context,p[1],map(body.get("input")),(String)body.get("idempotencyKey"),number(body.get("expectedRevision")));
                else throw new FcpException("NOT_FOUND","Unknown API endpoint");
            }
            send(x,200,Json.obj("code",200,"msg","success","data",result));
        } catch(FcpException e) {
            int status=switch(e.code){
                case "UNAUTHENTICATED"->401;
                case "PERMISSION_DENIED","USER_REQUIRED","TASK_IDENTITY_REQUIRED","DOWNLOAD_DENIED","FIELD_DENIED"->403;
                case "NOT_FOUND","CONFIG_NOT_FOUND"->404;
                case "INVALID_ARGUMENT","MANIFEST","SCHEMA","TYPE","HOST_IMPLEMENTATION_DEPENDENCY","PLUGIN_DEPENDENCY_METADATA","INVALID_INPUT","INVALID_ENCODING","INVALID_SOURCE","IDEMPOTENCY_REQUIRED"->400;
                case "VERSION_CONFLICT","PLUGIN_DISABLED","STALE_COMPOSITION","PLAN_EXPIRED","IDEMPOTENCY_CONFLICT","STALE_PREVIEW","PREVIEW_EXPIRED","VALIDATION_FAILED","CONFLICT","ADMISSION_CLOSED"->409;
                case "SOURCE_LIMIT","CURSOR_LIMIT"->413;
                case "RESOURCE_LIMIT"->429;
                case "DEADLINE_EXCEEDED","DEADLINE"->504;
                default->503;
            };
            send(x,status,Json.obj("code",status,"msg",status>=500?"Plugin operation unavailable; inspect operation receipt":e.getMessage(),"data",Json.obj("fcpCode",e.code)));
        } catch(IllegalArgumentException|ClassCastException e){send(x,400,Json.obj("code",400,"msg","Invalid request"));}
        catch(Exception e){send(x,503,Json.obj("code",503,"msg","Plugin host unavailable"));}
        finally{x.close();}
    }
    private ExchangeService createExchange(Map<String,Object> config,Path root)throws IOException {
        if(!config.containsKey("exchangeFixture"))return null;
        if(!config.containsKey("storageJdbc"))throw new IllegalArgumentException("Exchange requires explicit persistent MySQL storage");
        var fixture=obj(config,"exchangeFixture");String classId=str(fixture.get("classId"));
        if(!classId.matches("[A-Za-z0-9_.-]{1,100}"))throw new IllegalArgumentException("Invalid fixture class");
        List<ExchangeService.Config> configs=List.of(
            new ExchangeService.Config("roster-fixture","roster","class:"+classId,"campus-v1-csv","campus-v1-fixture","1",Set.of("student_code","full_name","class_code"),"campus-fixture","roster-fixture-sync"),
            new ExchangeService.Config("exam-fixture","mock-exam","class:"+classId,"campus-v1-csv","exam-v1-fixture","1",Set.of("exam_code","title","class_code"),"exam-fixture","exam-fixture-sync"));
        Map<String,ExchangeService.Connector> connectors=new HashMap<>();
        for(String kind:List.of("campus","exam"))if(fixture.containsKey(kind+"Api"))connectors.put(kind+"-fixture",new LoopbackConnector(URI.create(str(fixture.get(kind+"Api")))));
        var execution=new ExchangeService.ExecutionPort(){
            public String version(TrustedContext c,String plugin){var enabled=runtime.enabledVersion(c,plugin);return enabled.version()+":"+enabled.digest();}
            public Object call(TrustedContext c,String plugin,String operation,Map<String,Object> input,String key){return runtime.invoke(c,plugin,operation,input,key,null);}
        };
        return new ExchangeService(authority,execution,new FileStateJournal(root.resolve("exchange")),configs,connectors);
    }
    private static void fields(Map<String,Object> body,Set<String> fields){if(!fields.containsAll(body.keySet()))throw new FcpException("INVALID_ARGUMENT","Unknown request field");}
    private void staticResource(HttpExchange x)throws IOException {
        try {
            String path=x.getRequestURI().getRawPath();String name=switch(path){case "/dev/","/dev/index.html"->"index.html";case "/dev/app.js"->"app.js";case "/dev/runtime.js"->"runtime.js";case "/dev/style.css"->"style.css";default->null;};
            if(!"GET".equals(x.getRequestMethod())||name==null){send(x,404,Map.of("code",404));return;}
            try(InputStream input=DevHost.class.getResourceAsStream("/dev-ui/"+name)){
                if(input==null){send(x,404,Map.of("code",404));return;}
                byte[] bytes=input.readAllBytes();
                x.getResponseHeaders().set("Content-Type",name.endsWith("html")?"text/html; charset=utf-8":name.endsWith("js")?"text/javascript; charset=utf-8":"text/css; charset=utf-8");
                x.getResponseHeaders().set("Cache-Control","no-store");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
                x.getResponseHeaders().set("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
                x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);
            }
        } finally{x.close();}
    }
    private static String header(HttpExchange x,String key){String value=x.getRequestHeaders().getFirst(key);if(value==null)throw new FcpException("INVALID_ARGUMENT","Missing required header");return value;}
    private static Map<String,Object> readBody(HttpExchange x)throws IOException {byte[] bytes=x.getRequestBody().readNBytes(1024*1024+1);if(bytes.length>1024*1024)throw new FcpException("INVALID_ARGUMENT","Body too large");return map(Json.parse(new String(bytes,StandardCharsets.UTF_8)));}
    private static void send(HttpExchange x,int code,Object result)throws IOException {byte[] data=Json.write(result).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(code,data.length);x.getResponseBody().write(data);}
    public void close()throws Exception {server.stop(0);executor.shutdownNow();recovery.close();if(exchange!=null)exchange.close();settings.close();lifecycle.close();runtime.close();}
    public static void main(String[] args)throws Exception {
        if(args.length!=4||!args[0].equals("--development")||"production".equalsIgnoreCase(System.getenv("FCP_ENV")))
            throw new IllegalArgumentException("Usage: DevHost --development <private-identities.json> <state-directory> <port>; forbidden in production");
        DevHost host=new DevHost(Path.of(args[2]),Path.of(args[1]),Integer.parseInt(args[3]));
        Runtime.getRuntime().addShutdownHook(new Thread(()->{try{host.close();}catch(Exception ignored){}}));host.start();
        System.out.println("Development-only FCP API listening on loopback port "+host.port());
    }
    private static final class Authority implements HostCapabilities {
        private final Path privateConfig;private final Path audit;
        Authority(Path privateConfig,Path root)throws IOException{this.privateConfig=privateConfig;Files.createDirectories(root);audit=root.resolve("audit.jsonl");}
        private Map<String,Object> configuration(){try{if(Files.isSymbolicLink(privateConfig)||Files.size(privateConfig)>1024*1024)throw new IOException("Identity config changed");Map<String,Object> config=map(Json.parse(Files.readString(privateConfig)));if(!Boolean.TRUE.equals(config.get("development")))throw new FcpException("PERMISSION_DENIED","Development authentication disabled");return config;}catch(IOException e){throw new FcpException("PERMISSION_DENIED","Development identity unavailable");}}
        private long revision(){return java.nio.ByteBuffer.wrap(java.util.HexFormat.of().parseHex(VerifiedPackageRepository.hash(Json.write(configuration()).getBytes(StandardCharsets.UTF_8)))).getLong()&0x1fffffffffffffL;}
        TrustedContext recoveryContext(String tenant){return new TrustedContext(UUID.randomUUID().toString(),UUID.randomUUID().toString(),tenant,new TrustedContext.Principal(TrustedContext.Kind.TASK,"fcp-control-recovery"),revision(),Set.of(),clock().instant().plusSeconds(60));}
        String verifyEnablement(TrustedContext context,fcp.plugin.PluginManifest manifest,String digest,Map<String,fcp.plugin.PluginManifest> closure) {
            List<Object> approvals=new ArrayList<>();approvals.add(digest);
            for(var required:manifest.requires()) {
                List<Object> matches=new ArrayList<>();
                for(Object raw:arr(configuration(),"identities")) {
                    var identity=map(raw);if(!context.tenantId().equals(identity.get("tenantId")))continue;
                    for(Object item:arr(identity,"grants")) {var grant=map(item);if(manifest.pluginId().equals(grant.get("plugin"))&&required.id().equals(grant.get("capability"))&&"invoke".equals(grant.get("operation")))matches.add(grant);}
                }
                if(matches.isEmpty())throw new FcpException("CAPABILITY_NOT_GRANTED","Missing development platform capability approval");approvals.add(matches);
            }
            return VerifiedPackageRepository.hash(Json.write(approvals).getBytes(StandardCharsets.UTF_8));
        }
        public Descriptor descriptor(){return new Descriptor("development","1.0","fcp.host/1","embedded-single-instance",Map.of("ui.slot.dashboard.status","1.0"));}
        public Clock clock(){return Clock.systemUTC();}
        TrustedContext authenticate(String token){
            if(token==null)throw new FcpException("UNAUTHENTICATED","Development authorization required");
            String raw=token.startsWith("Bearer ")?token.substring(7):token;
            for(Object value:arr(configuration(),"identities")){var id=map(value);String expected=str(id.get("token"));if(expected.length()<32)throw new FcpException("UNAUTHENTICATED","Development token configuration invalid");
                if(java.security.MessageDigest.isEqual(raw.getBytes(StandardCharsets.UTF_8),expected.getBytes(StandardCharsets.UTF_8))) {
                    Set<String> scopes=new HashSet<>();for(Object s:arr(id,"scopes"))scopes.add(str(s));
                    return new TrustedContext(UUID.randomUUID().toString(),UUID.randomUUID().toString(),str(id.get("tenantId")),new TrustedContext.Principal(TrustedContext.Kind.USER,str(id.get("userId"))),revision(),scopes,clock().instant().plusSeconds(60));
                }}throw new FcpException("UNAUTHENTICATED","Development authorization invalid");
        }
        private Map<String,Object> identity(TrustedContext c){for(Object v:arr(configuration(),"identities")){var id=map(v);if(id.get("tenantId").equals(c.tenantId())&&id.get("userId").equals(c.principal().id())&&c.principal().kind()==TrustedContext.Kind.USER)return id;}throw new FcpException("PERMISSION_DENIED","Unknown development principal");}
        Set<String> permissions(TrustedContext c){Set<String> result=new HashSet<>();for(Object p:arr(identity(c),"permissions"))result.add(str(p));return result;}
        public Decision authorize(TrustedContext c,Grant g){
            if(c.principal().kind()==TrustedContext.Kind.TASK) {
                boolean tenant=arr(configuration(),"identities").stream().anyMatch(v->c.tenantId().equals(map(v).get("tenantId")));
                return new Decision(tenant&&c.principal().id().equals("fcp-control-recovery")&&g.capability().equals("plugin.lifecycle")&&Set.of("reconcile","enable","disable","status").contains(g.operation())&&g.dataScope().equals("tenant:"+c.tenantId()),revision());
            }
            var id=identity(c);Set<String> permissions=permissions(c);
            if(!g.dataScope().equals("tenant:"+c.tenantId())&&(!c.dataScopes().contains(g.dataScope())||!arr(id,"scopes").contains(g.dataScope())))return new Decision(false,revision());
            if(g.capability().equals("plugin.lifecycle")){String permission=Set.of("status","view").contains(g.operation())?"fcp:plugin:view":Set.of("install","uninstall").contains(g.operation())?"fcp:plugin:install":"fcp:plugin:manage";return new Decision(g.dataScope().equals("tenant:"+c.tenantId())&&permissions.contains(permission),revision());}
            for(Object value:arr(id,"grants")){var grant=map(value);if(g.pluginId().equals(grant.get("plugin"))&&g.capability().equals(grant.get("capability"))&&g.operation().equals(grant.get("operation"))&&g.dataScope().equals(grant.get("dataScope"))&&permissions.contains(grant.get("permission")))return new Decision(true,revision());}
            return new Decision(false,revision());
        }
        public synchronized void audit(Audit e){try{Files.writeString(audit,Json.write(Json.obj("requestId",e.requestId(),"tenantId",e.tenantId(),"principal",e.principalId(),"pluginId",e.pluginId(),"action",e.action(),"outcome",e.outcome(),"at",e.occurredAt().toString()))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(IOException error){throw new FcpException("AUDIT_UNAVAILABLE","Development audit failed",error);}}
    }
}
