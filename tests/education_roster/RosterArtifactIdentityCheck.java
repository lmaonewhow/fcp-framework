import fcp.education.RosterArtifactIdentity;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Pure artifact/parser boundaries. Temporary bytes are not a native host acceptance claim. */
public final class RosterArtifactIdentityCheck {
    static int checks;
    static void check(boolean value){checks++;if(!value)throw new AssertionError("artifact check "+checks);}
    static Object invoke(String name,Class<?>[] types,Object...values) {
        try{Method method=RosterArtifactIdentity.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(null,values);}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException error)throw error;throw new IllegalStateException(failure);}
        catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
    }
    static void denied(Runnable work){try{work.run();throw new AssertionError("Artifact spoof accepted");}catch(IllegalStateException expected){check(expected.getMessage().equals("ROSTER_EXECUTION_ARTIFACT_MISMATCH"));}}
    public static void main(String[] args)throws Exception {
        Path directory=Files.createTempDirectory("roster-artifact-");Path jar=directory.resolve("test archive.jar");byte[] content="Synthetic parser-only bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(jar,content);
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        Class<?>[] launch={String[].class},hash={Path.class,String.class},source={String.class};
        try {
            check(invoke("launchArchive",launch,(Object)new String[]{"-Xms128m","-Xmx1024m","-jar",jar.toString(),"--application-option"}).equals(jar.toRealPath()));
            check(invoke("launchArchive",launch,(Object)new String[]{"-Duser.timezone=UTC","-Xms128m","-Xmx1024m","-jar",jar.toString(),"--application-option"}).equals(jar.toRealPath()));
            // Preserve the existing accepted heap-size syntax and repetition policy.
            check(invoke("launchArchive",launch,(Object)new String[]{"-Xms128m","-Xms256m","-Xmx1G","-Duser.timezone=UTC","-jar",jar.toString()}).equals(jar.toRealPath()));
            for(String option:List.of("-Duser.timezone=GMT","-Duser.timezone=Etc/UTC","-Duser.timezone=utc","-Duser.timezone=UTC ","-Duser.timezone=UTC=UTC","-Duser.timezone","-Djava.class.path=other.jar","-Djava.system.class.loader=Other","-agentlib:jdwp","-agentpath:/tmp/agent.so","-Xbootclasspath/a:other.jar","--class-path=other.jar"))
                denied(()->invoke("launchArchive",launch,(Object)new String[]{option,"-jar",jar.toString()}));
            denied(()->invoke("launchArchive",launch,(Object)new String[]{"-Duser.timezone=UTC","-Duser.timezone=UTC","-jar",jar.toString()}));
            denied(()->invoke("launchArchive",launch,(Object)new String[]{"-Duser.timezone=UTC","-Duser.timezone=GMT","-jar",jar.toString()}));
            check(invoke("verifyDigest",hash,jar,digest).equals(digest));
            denied(()->invoke("verifyDigest",hash,jar,"f".repeat(64)));
            denied(()->invoke("verifyDigest",hash,jar,digest.toUpperCase(Locale.ROOT)));
            denied(()->invoke("verifyDigest",hash,jar,""));
            for(String[] invalid:List.of(new String[]{"-cp","classes","Main","-jar",jar.toString()},new String[]{"Main","-jar",jar.toString()},new String[]{"-Dfoo=-jar","-jar",jar.toString()},new String[]{"@args","-jar",jar.toString()},new String[]{"--module","app/Main","-jar",jar.toString()},new String[]{"-javaagent:other.jar","-jar",jar.toString()},new String[]{"-jar"},new String[]{"-jar",directory.toString()},new String[]{"-jar",directory.resolve("missing.jar").toString()}))denied(()->invoke("launchArchive",launch,(Object)invalid));
            check(invoke("codeArchive",source,jar.toUri().toASCIIString()).equals(jar.toRealPath()));
            String fileUri=jar.toUri().toASCIIString();
            check(invoke("codeArchive",source,"jar:nested:"+fileUri.substring("file:".length())+"/!BOOT-INF/classes/!/").equals(jar.toRealPath()));
            check(invoke("codeArchive",source,"jar:"+fileUri+"!/BOOT-INF/classes!/").equals(jar.toRealPath()));
            denied(()->invoke("codeArchive",source,directory.toUri().toASCIIString()));
            denied(()->invoke("codeArchive",source,"https://example.invalid/approved.jar"));
            denied(()->invoke("codeArchive",source,fileUri+"?artifact=approved"));
            denied(()->invoke("codeArchive",source,"jar:nested:"+fileUri.substring("file:".length())+"/!BOOT-INF/lib/other.jar!/"));
            Path link=directory.resolve("link.jar");Files.createSymbolicLink(link,jar.getFileName());try{denied(()->invoke("launchArchive",launch,(Object)new String[]{"-jar",link.toString()}));}finally{Files.delete(link);}
            // This check is launched from classes, so production verification must fail closed.
            denied(()->RosterArtifactIdentity.verifyCurrentArchive(digest,RosterArtifactIdentityCheck.class));
            Files.writeString(jar,"A different artifact");denied(()->invoke("verifyDigest",hash,jar,digest));
        } finally {Files.deleteIfExists(jar);Files.deleteIfExists(directory);}
        System.out.println("PASS roster artifact pure boundaries: "+checks+" assertions; native fat-JAR startup NOT_RUN");
    }
}
