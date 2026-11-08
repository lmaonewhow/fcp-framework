import fcp.*;import fcp.control.*;import fcp.host.*;import fcp.release.routing.*;import java.nio.file.*;import java.time.*;import java.util.*;
public class ReleaseRenewalTest {
 static class Time extends Clock {Instant now=Instant.parse("2026-01-01T00:00:00Z");public Instant instant(){return now;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}}
 static TrustedContext ctx(Time time,long auth){return new TrustedContext("req","trace","tenant",new TrustedContext.Principal(TrustedContext.Kind.USER,"user"),auth,Set.of(),time.instant().plusSeconds(20));}
 public static void main(String[] args)throws Exception {Time time=new Time();var router=new DurableReleaseRouter(new FileStateJournal(Files.createTempDirectory("renew").resolve("state")),new byte[32],time,Duration.ofMinutes(5));
  router.put(new ReleasePolicy("p","a","b",0,Set.of(),Set.of(),true,0),0);var first=router.decide(ctx(time,1));time.now=time.now.plusSeconds(10);router.put(new ReleasePolicy("p","a","b",10000,Set.of(),Set.of(),true,0),router.revision());
  var renewed=router.renew(ctx(time,1),first.affinity());RealRuntimeTest.check(renewed.releaseId().equals("a")&&renewed.expiresAt().isAfter(first.expiresAt()),"renewal extends same release without re-bucketing");
  PluginMultiVersionTest.denied("STALE_RELEASE_AFFINITY",()->router.renew(ctx(time,2),renewed.affinity()));time.now=time.now.plusSeconds(11);
  PluginMultiVersionTest.denied("STALE_RELEASE_AFFINITY",()->router.renew(ctx(time,1),first.affinity()));RealRuntimeTest.check(router.resume(ctx(time,1),renewed.affinity()).releaseId().equals("a"),"renewed token survives original expiration");
  router.put(new ReleasePolicy("p","a","b",10000,Set.of(),Set.of(),true,1),router.revision());PluginMultiVersionTest.denied("STALE_RELEASE_AFFINITY",()->router.renew(ctx(time,1),renewed.affinity()));
  System.out.println("PASS same-release affinity renewal; expired, authorization-revised and epoch-revoked tokens rejected");
 }
}
