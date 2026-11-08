import dev.fcp.host.*;
import fcp.host.*;
import fcp.control.FileStateJournal;
import fcp.release.version.*;
import fcp.release.control.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public class SystemReleaseHostTest {
    static int checks;
    static void yes(boolean b){checks++;if(!b)throw new AssertionError();}
    static void denied(Throwing r){checks++;try{r.run();throw new AssertionError("expected refusal");}catch(AssertionError e){throw e;}catch(Exception expected){}}
    interface Throwing{void run()throws Exception;}
    static class Host implements HostCapabilities {
        boolean allowed=true;public Clock clock(){return Clock.systemUTC();}
        public Descriptor descriptor(){return new Descriptor("test","1","fcp.host/1","embedded-single-instance",Map.of());}
        public Decision authorize(TrustedContext c,Grant g){return new Decision(allowed&&g.capability().equals("system.release")&&Set.of("view","manage").contains(g.operation()),1);}
        public void audit(Audit a){}
    }
    static TrustedContext ctx(TrustedContext.Kind kind,Instant deadline){return new TrustedContext("r","t","000000",new TrustedContext.Principal(kind,"user"),1,Set.of(),deadline);}
    public static void main(String[] args)throws Exception {
        Path tmp=Files.createTempDirectory("release-host-test");
        try(FileStateJournal journal=new FileStateJournal(tmp)) {
            var provider=new JournalVersionProvider("test",journal);var host=new Host();var app=new SystemVersionApplication(host,provider);
            var context=ctx(TrustedContext.Kind.USER,Instant.now().plusSeconds(60));
            var artifact=new SystemArtifact("backend",SystemArtifact.Kind.HOST_BACKEND,"host","1","a".repeat(64),1,"file:///unverified",Map.of(),Map.of());
            app.registerArtifact(context,VersionCodec.artifact(artifact));yes(provider.artifact("backend").isPresent());
            host.allowed=false;denied(()->app.list(context));denied(()->app.registerArtifact(context,VersionCodec.artifact(artifact)));host.allowed=true;
            denied(()->app.list(ctx(TrustedContext.Kind.TASK,Instant.now().plusSeconds(60))));denied(()->app.list(ctx(TrustedContext.Kind.USER,Instant.EPOCH)));
            var a=new SystemRelease("old","1",Map.of("host","backend"),Map.of("schemaMin","5","schemaMax","8"),Map.of());
            var b=new SystemRelease("new","2",Map.of("host","backend"),Map.of("schemaMin","6","schemaMax","9"),Map.of());
            app.registerRelease(context,VersionCodec.release(a));app.registerRelease(context,VersionCodec.release(b));
            String da=VersionCodec.sha256(VersionCodec.canonical(VersionCodec.release(a))),db=VersionCodec.sha256(VersionCodec.canonical(VersionCodec.release(b)));
            var plan=new ReleasePlan("test","old","new",da,db,7,5,8,6,9);
            new SystemReleasePreflight(provider,()->new SystemReleasePreflight.SchemaState(7,false)).verify(plan);yes(true);
            denied(()->new SystemReleasePreflight(provider,()->new SystemReleasePreflight.SchemaState(7,true)).verify(plan));
            denied(()->new SystemReleasePreflight(provider,()->new SystemReleasePreflight.SchemaState(8,false)).verify(plan));
            var forged=new ReleasePlan("test","old","new",da,db,7,0,99,0,99);
            denied(()->new SystemReleasePreflight(provider,()->new SystemReleasePreflight.SchemaState(7,false)).verify(forged));
            var wrong=new ReleasePlan("test","old","new","b".repeat(64),db,7,5,8,6,9);
            denied(()->new SystemReleasePreflight(provider,()->new SystemReleasePreflight.SchemaState(7,false)).verify(wrong));
            yes(((List<?>)app.list(context).get("releases")).size()==2);
        }
        System.out.println("PASS "+checks+" host release assertions; RuoYi/MySQL NOT_RUN");
    }
}
