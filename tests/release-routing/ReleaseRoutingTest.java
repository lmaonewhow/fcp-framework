import fcp.*;
import fcp.host.*;
import fcp.control.*;
import fcp.release.routing.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public class ReleaseRoutingTest {
    static int checks;
    static final byte[] KEY=new byte[32], IDENTITY=new byte[32];
    static final Clock CLOCK=Clock.systemUTC();
    static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    interface Run{void run()throws Exception;}
    static void deny(Run run,String message)throws Exception{try{run.run();throw new AssertionError(message);}catch(FcpException|IllegalArgumentException expected){checks++;}}
    static final class MutableClock extends Clock {
        Instant now=Instant.now(); public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return now;}
    }
    static TrustedContext context(String tenant){return new TrustedContext(UUID.randomUUID().toString(),"trace",tenant,new TrustedContext.Principal(TrustedContext.Kind.USER,"u1"),1,Set.of("class-a"),Instant.now().plusSeconds(300));}
    static ReleasePolicy policy(int percent,Set<String> allow,Set<String> exclude,boolean enabled,long epoch){return new ReleasePolicy("app","v1","v2",percent,allow,exclude,enabled,epoch);}
    static HttpServer server(String release)throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/call",exchange->{
            byte[] body=exchange.getRequestBody().readAllBytes();
            try{
                TrustedContext identity=ReleaseHttpTransport.verifyIdentity(IDENTITY,CLOCK,release,exchange.getRequestHeaders().getFirst("X-FCP-Release-Context"),exchange.getRequestMethod(),exchange.getRequestURI().getPath(),body);
                if(exchange.getRequestHeaders().containsKey("Authorization"))throw new AssertionError("Host token leaked");
                byte[] result=(release+":"+identity.tenantId()+":"+identity.principal().id()).getBytes();
                exchange.getResponseHeaders().set("X-FCP-Release",release);
                exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);
            }catch(RuntimeException failure){exchange.sendResponseHeaders(403,-1);}finally{exchange.close();}
        });server.start();return server;
    }
    public static void main(String[] args)throws Exception{
        Arrays.fill(KEY,(byte)42);Arrays.fill(IDENTITY,(byte)13);
        Path directory=Files.createTempDirectory("fcp-release-routing-");
        String affinity;int savedBucket;
        HttpServer old=server("v1"), candidate=server("v2");
        try{
            try(FileStateJournal journal=new FileStateJournal(directory)){
                DurableReleaseRouter router=new DurableReleaseRouter(journal,KEY,CLOCK,Duration.ofMinutes(5));
                deny(()->router.decide(context("a")),"Unconfigured allowed");
                router.put(policy(1000,Set.of("a","excluded"),Set.of("excluded"),true,0),0);
                check(router.decide(context("a")).releaseId().equals("v2"),"Allowlist");
                check(router.decide(context("excluded")).releaseId().equals("v1"),"Exclude first");
                Set<String> before=new HashSet<>();for(int i=0;i<500;i++)if(router.decide(context("t"+i)).releaseId().equals("v2"))before.add("t"+i);
                check(!before.isEmpty(),"Bucket coverage");savedBucket=router.bucket("a");
                affinity=router.decide(context("a")).affinity();
                router.put(policy(2000,Set.of("a"),Set.of(),true,0),1);
                for(String tenant:before)check(router.decide(context(tenant)).releaseId().equals("v2"),"Expansion shuffled");
                check(router.bucket("a")==savedBucket,"Revision changed bucket");
                check(router.resume(context("a"),affinity).releaseId().equals("v2"),"Affinity lost");
                deny(()->router.resume(context("b"),affinity),"Cross tenant accepted");
                TrustedContext other=new TrustedContext("other","trace","a",new TrustedContext.Principal(TrustedContext.Kind.USER,"u2"),1,Set.of(),Instant.now().plusSeconds(300));
                deny(()->router.resume(other,affinity),"Cross principal accepted");
                TrustedContext changed=new TrustedContext("changed","trace","a",new TrustedContext.Principal(TrustedContext.Kind.USER,"u1"),2,Set.of(),Instant.now().plusSeconds(300));
                deny(()->router.resume(changed,affinity),"Revoked authorization accepted");
                MutableClock expiryClock=new MutableClock();
                DurableReleaseRouter expiryRouter=new DurableReleaseRouter(journal,KEY,expiryClock,Duration.ofSeconds(1));
                String shortAffinity=expiryRouter.decide(context("a")).affinity();expiryClock.now=expiryClock.now.plusSeconds(2);
                deny(()->expiryRouter.resume(context("a"),shortAffinity),"Expired affinity accepted");
                deny(()->router.resume(context("a"),affinity.substring(0,affinity.length()-2)+"zz"),"Tampering accepted");
                deny(()->router.put(policy(0,Set.of(),Set.of(),true,0),0),"Stale CAS accepted");
                ReleaseHttpTransport transport=new ReleaseHttpTransport(router,List.of(new ReleaseHttpTransport.Endpoint("v1",URI.create("http://127.0.0.1:"+old.getAddress().getPort()),Set.of("/call")),new ReleaseHttpTransport.Endpoint("v2",URI.create("http://127.0.0.1:"+candidate.getAddress().getPort()),Set.of("/call"))),IDENTITY,CLOCK);
                check(new String(transport.invoke(context("a"),affinity,"POST","/call","{}".getBytes()).body()).equals("v2:a:u1"),"Real candidate route");
                router.put(policy(0,Set.of("a"),Set.of("stable"),true,0),2);
                check(new String(transport.invoke(context("stable"),null,"POST","/call","{}".getBytes()).body()).equals("v1:stable:u1"),"Real stable route");
                deny(()->transport.invoke(context("stable"),null,"GET","/unauthorized",new byte[0]),"Arbitrary path");
                check(transport.observations().get("v2").successes()==1,"Observed real candidate");
                deny(()->new ReleaseHttpTransport.Endpoint("v3",URI.create("http://example.com"),Set.of("/call")),"Insecure external origin");
                deny(()->new ReleaseHttpTransport.Endpoint("v3",URI.create("https://example.com"),Set.of("//evil")),"Authority escape");
                ReleaseHttpTransport mismatch=new ReleaseHttpTransport(router,List.of(new ReleaseHttpTransport.Endpoint("v2",URI.create("http://127.0.0.1:"+old.getAddress().getPort()),Set.of("/call"))),IDENTITY,CLOCK);
                try{mismatch.invoke(context("a"),null,"POST","/call","{}".getBytes());throw new AssertionError("Version mismatch accepted");}catch(java.io.IOException expected){checks++;}
                check(mismatch.observations().get("v2").unknown()==1,"Unknown write outcome missing");
                router.put(policy(0,Set.of(),Set.of("a"),true,1),3);
                deny(()->router.resume(context("a"),affinity),"Revoked affinity accepted");
                router.put(policy(0,Set.of(),Set.of(),false,1),4);
                deny(()->router.decide(context("a")),"Emergency disabled accepted");
            }
            try(FileStateJournal journal=new FileStateJournal(directory)){
                DurableReleaseRouter router=new DurableReleaseRouter(journal,KEY,CLOCK,Duration.ofMinutes(5));
                check(router.revision()==5,"Durability revision");check(router.bucket("a")==savedBucket,"Durability bucket");deny(()->router.resume(context("a"),affinity),"Restart bypassed disable");
            }
            System.out.println("PASS release routing checks="+checks+"; real loopback HTTP v1/v2, Java durable journal. Native RuoYi/MySQL/browser NOT_RUN.");
        }finally{old.stop(0);candidate.stop(0);}
    }
}
