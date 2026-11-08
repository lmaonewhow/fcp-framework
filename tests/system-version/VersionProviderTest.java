import fcp.release.version.*;
import fcp.FcpException;
import fcp.Json;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class VersionProviderTest {
    static int checks;
    static void check(boolean condition,String what){checks++;if(!condition)throw new AssertionError(what);}
    static void fails(Runnable action,String code){checks++;try{action.run();throw new AssertionError("Expected "+code);}catch(FcpException e){if(!e.code.equals(code))throw new AssertionError("Expected "+code+" got "+e.code,e);}}
    static void invalid(Runnable action){checks++;try{action.run();throw new AssertionError("Expected invalid input");}catch(IllegalArgumentException expected){}}
    static SystemArtifact artifact(String id,String version,String payload){byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);return new SystemArtifact(id,SystemArtifact.Kind.HOST_BACKEND,"host",version,VersionCodec.sha256(bytes),bytes.length,"artifact://host/"+id,Map.of("api","1","schema","1"),Map.of("sourceCommit","optional-trace-metadata"));}
    static SystemRelease release(String id,String version,String artifact){return new SystemRelease(id,version,Map.of("backend",artifact),Map.of("api","1"),Map.of());}
    static void contract(VersionProvider p){
        var a=artifact("host-v1","1","bytes");var r=release("release-v1","1","host-v1");
        check(p.artifact("missing").isEmpty(),"missing artifact");check(p.release("missing").isEmpty(),"missing release");
        check(p.registerArtifact(a).equals(a),"register artifact");check(p.registerArtifact(a).equals(a),"idempotent artifact");
        check(p.artifact(a.id()).orElseThrow().equals(a),"read artifact");
        fails(()->p.registerArtifact(artifact("host-v1","1","different")),"VERSION_CONFLICT");
        fails(()->p.registerArtifact(artifact("alias","1","different")),"VERSION_CONFLICT");
        check(p.registerRelease(r).equals(r),"register release");check(p.registerRelease(r).equals(r),"idempotent release");
        check(p.release(r.id()).orElseThrow().equals(r),"read release");
        fails(()->p.registerRelease(release("release-v1","2","host-v1")),"VERSION_CONFLICT");
        check(p.artifacts().equals(List.of(a)),"artifact listing");check(p.releases().equals(List.of(r)),"release listing");
    }
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("fcp-version-test-");
        try {
            var store=new FileVersionProvider("local",dir.resolve("local"));contract(store);
            var again=new FileVersionProvider("local",dir.resolve("local"));check(again.release("release-v1").equals(store.release("release-v1")),"restart durability");
            fails(()->store.registerRelease(release("bad","bad","missing")),"ARTIFACT_NOT_FOUND");
            var registry=new VersionProviders();registry.register(store);check(registry.require("local")==store,"provider discovery");invalid(()->registry.register(again));invalid(()->registry.require("unknown"));
            invalid(()->store.artifact("../host"));invalid(()->artifact("../bad","1","x"));
            Path bytes=dir.resolve("artifact.bin");Files.writeString(bytes,"bytes");FileVersionProvider.verifyBytes(artifact("a","1","bytes"),bytes);checks++;
            fails(()->FileVersionProvider.verifyBytes(artifact("a","1","other"),bytes),"ARTIFACT_INTEGRITY");
            var executor=Executors.newFixedThreadPool(4);
            try{var results=new ArrayList<Future<?>>();for(int i=0;i<20;i++)results.add(executor.submit(()->again.registerArtifact(artifact("host-v2","2","v2"))));for(var future:results)future.get();check(store.artifacts().size()==2,"concurrent idempotent writes");}finally{executor.shutdownNow();}
            Path entry=dir.resolve("local/artifacts/host-v1.json");String original=Files.readString(entry);Files.writeString(entry,original.replace("optional-trace-metadata","tampered-metadata"));fails(()->store.artifact("host-v1"),"VERSION_CORRUPT");Files.writeString(entry,original);
            Files.createSymbolicLink(dir.resolve("local/artifacts/symlink.json"),entry);fails(()->store.artifact("symlink"),"VERSION_CORRUPT");Files.delete(dir.resolve("local/artifacts/symlink.json"));
            Path orphan=dir.resolve("local/artifacts/.pending-crash.tmp");Files.writeString(orphan,"incomplete");check(store.artifacts().size()==2,"uncommitted crash file ignored");
            try(var journal=new fcp.control.FileStateJournal(dir.resolve("journal"))){contract(new JournalVersionProvider("journal",journal));}
            try(var journal=new fcp.control.FileStateJournal(dir.resolve("journal"))){check(new JournalVersionProvider("journal",journal).releases().size()==1,"journal restart");}
            journalFaults();
            http(dir.resolve("remote"));
            System.out.println("PASS checks="+checks+"; real file persistence and loopback HTTP; native RuoYi/MySQL/browser NOT_RUN");
        }finally{try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    static void journalFaults() {
        class FaultJournal implements fcp.control.StateJournal {
            Snapshot state=new Snapshot(0,null);boolean fail=true;
            public Snapshot load(){return state;}
            public Snapshot compareAndSet(long revision,String json)throws java.io.IOException {
                if(revision!=state.revision())throw new java.io.IOException("JOURNAL_CONFLICT");
                state=new Snapshot(revision+1,json);
                if(fail){fail=false;throw new java.io.IOException("JOURNAL_OUTCOME_UNKNOWN");}
                return state;
            }
            public void close(){}
        }
        var journal=new FaultJournal();var provider=new JournalVersionProvider("fault",journal);var a=artifact("unknown","1","x");
        fails(()->provider.registerArtifact(a),"VERSION_WRITE_UNCERTAIN");
        check(provider.artifact("unknown").orElseThrow().equals(a),"unknown commit reconciled by reload");
        check(provider.registerArtifact(a).equals(a)&&journal.state.revision()==1,"retry reconciled without duplicate write");
        journal.state=new fcp.control.StateJournal.Snapshot(1,"{\"format\":1,\"sha256\":\"invalid\",\"value\":{}}");
        fails(provider::artifacts,"VERSION_CORRUPT");
    }
    static void http(Path root)throws Exception {
        var remote=new FileVersionProvider("server",root);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/v1/",exchange->{
            String path=exchange.getRequestURI().getPath().substring(4);int code=200;Object value=null;
            try {
                String[] parts=path.split("/");boolean artifacts=parts[0].equals("artifacts");
                if(!artifacts&&!parts[0].equals("releases"))throw new IllegalArgumentException();
                if(exchange.getRequestMethod().equals("PUT")){Object parsed=Json.parse(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));value=artifacts?VersionCodec.artifact(remote.registerArtifact(VersionCodec.artifact(parsed))):VersionCodec.release(remote.registerRelease(VersionCodec.release(parsed)));code=201;}
                else if(parts.length==1)value=artifacts?remote.artifacts().stream().map(VersionCodec::artifact).toList():remote.releases().stream().map(VersionCodec::release).toList();
                else {value=artifacts?remote.artifact(parts[1]).map(VersionCodec::artifact).orElse(null):remote.release(parts[1]).map(VersionCodec::release).orElse(null);if(value==null)code=404;}
            }catch(FcpException e){code=e.code.equals("VERSION_CONFLICT")?409:400;value=Map.of("error",e.code);}catch(Exception e){code=400;value=Map.of("error","invalid");}
            byte[] body=Json.write(value).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(code,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.createContext("/redirect/",exchange->{exchange.getResponseHeaders().set("Location","/v1/artifacts");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/wrong/",exchange->{byte[] b=Json.write(VersionCodec.artifact(artifact("different-id","1","x"))).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,b.length);exchange.getResponseBody().write(b);exchange.close();});
        server.createContext("/large/",exchange->{byte[] b=new byte[1024*1024+1];exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,b.length);try{exchange.getResponseBody().write(b);}finally{exchange.close();}});
        server.createContext("/schema/",exchange->{byte[] b="{\"unexpected\":true}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,b.length);exchange.getResponseBody().write(b);exchange.close();});
        server.createContext("/slow/",exchange->{try{Thread.sleep(500);exchange.sendResponseHeaders(200,-1);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});
        server.createContext("/mime/",exchange->{exchange.getResponseHeaders().set("Content-Type","text/html");exchange.sendResponseHeaders(200,-1);exchange.close();});
        server.start();
        try{
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort();
            var p=new HttpVersionProvider("external",URI.create(endpoint+"/v1/"),true,Duration.ofSeconds(3),true);contract(p);
            var readonly=new HttpVersionProvider("ro",URI.create(endpoint+"/v1/"),false,Duration.ofSeconds(3),true);check(readonly.capabilities().equals(Set.of(VersionProvider.Capability.READ)),"read-only capability");fails(()->readonly.registerArtifact(artifact("x","3","x")),"VERSION_UNSUPPORTED");
            fails(()->new HttpVersionProvider("redirect",URI.create(endpoint+"/redirect/"),false,Duration.ofSeconds(3),true).artifacts(),"VERSION_REMOTE_STATUS");
            fails(()->new HttpVersionProvider("wrong",URI.create(endpoint+"/wrong/"),false,Duration.ofSeconds(3),true).artifact("x"),"VERSION_REMOTE_PROTOCOL");
            fails(()->new HttpVersionProvider("large",URI.create(endpoint+"/large/"),false,Duration.ofSeconds(3),true).artifacts(),"VERSION_REMOTE_UNCERTAIN");
            invalid(()->new HttpVersionProvider("http",URI.create("http://example.com/v1/"),false,Duration.ofSeconds(3),true));
            invalid(()->new HttpVersionProvider("http",URI.create(endpoint+"/v1/"),false,Duration.ofSeconds(3),false));
            invalid(()->new HttpVersionProvider("http",URI.create("https://user:password@example.com/v1/"),false,Duration.ofSeconds(3),false));
            fails(()->new HttpVersionProvider("schema",URI.create(endpoint+"/schema/"),false,Duration.ofSeconds(3),true).artifact("x"),"VERSION_REMOTE_PROTOCOL");
            fails(()->new HttpVersionProvider("slow",URI.create(endpoint+"/slow/"),false,Duration.ofMillis(100),true).artifacts(),"VERSION_REMOTE_UNCERTAIN");
            fails(()->new HttpVersionProvider("mime",URI.create(endpoint+"/mime/"),false,Duration.ofSeconds(3),true).artifacts(),"VERSION_REMOTE_PROTOCOL");
        }finally{server.stop(0);executor.shutdownNow();}
    }
}
