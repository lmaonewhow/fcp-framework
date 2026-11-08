import fcp.education.RosterArtifactIdentity;
import java.io.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;

/** Actual child JVM and executable fixture-JAR checks, not native Spring/MySQL acceptance. */
public final class RosterArtifactLaunchCheck {
    private static int checks,launches;
    public static final class OutsideAnchor {}
    private static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    private static String digest(Path jar)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));}
    private static void add(JarOutputStream jar,String resource)throws Exception {
        jar.putNextEntry(new JarEntry(resource));
        try(InputStream input=RosterArtifactLaunchCheck.class.getResourceAsStream("/"+resource)){
            if(input==null)throw new IOException("Compiled class missing: "+resource);input.transferTo(jar);
        }
        jar.closeEntry();
    }
    private static void archive(Path target,boolean outside)throws Exception {
        Manifest manifest=new Manifest();manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0");
        if(!outside)manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS,"RosterArtifactLaunchCheck");
        try(JarOutputStream jar=new JarOutputStream(Files.newOutputStream(target),manifest)) {
            if(outside)add(jar,"RosterArtifactLaunchCheck$OutsideAnchor.class");
            else {add(jar,"RosterArtifactLaunchCheck.class");add(jar,"fcp/education/RosterArtifactIdentity.class");}
        }
    }
    private static void launch(Path directory,Path jar,String expected,List<String> prefix,boolean permitted,String label,String... extra)throws Exception {
        String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        List<String> command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin",executable).toString()));
        command.addAll(prefix);command.addAll(List.of("-jar",jar.toString(),"--verify-archive",expected));command.addAll(Arrays.asList(extra));
        execute(directory,command,permitted,label);
    }
    private static void execute(Path directory,List<String> command,boolean permitted,String label)throws Exception {
        ProcessBuilder builder=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        for(String key:List.of("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","JAVA_OPTS","CLASSPATH"))builder.environment().remove(key);
        Process child=builder.start();boolean completed=false;
        try {
            completed=child.waitFor(20,TimeUnit.SECONDS);
            check(completed,label+" timed out");
            String output=new String(child.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            launches++;
            System.out.println("CHILD "+label+" exit="+child.exitValue()+" expected="+(permitted?"VERIFIED":"REJECTED"));
            if(permitted&&child.exitValue()!=0)System.out.print(output);
            check(permitted?(child.exitValue()==0&&output.contains("ARCHIVE_VERIFIED "))
                :(child.exitValue()!=0&&output.contains("ROSTER_EXECUTION_ARTIFACT_MISMATCH")),label+" produced the wrong artifact decision");
        } finally {if(!completed&&child.isAlive()){child.destroyForcibly();child.waitFor(20,TimeUnit.SECONDS);}}
    }
    public static void main(String[] args)throws Exception {
        if(args.length>=2&&args[0].equals("--verify-archive")) {
            if(args.length==3) {
                try(URLClassLoader loader=new URLClassLoader(new java.net.URL[]{Path.of(args[2]).toUri().toURL()},null)) {
                    System.out.println("ARCHIVE_VERIFIED "+RosterArtifactIdentity.verifyCurrentArchive(args[1],loader.loadClass("RosterArtifactLaunchCheck$OutsideAnchor")));
                }
            } else System.out.println("ARCHIVE_VERIFIED "+RosterArtifactIdentity.verifyCurrentArchive(args[1],RosterArtifactLaunchCheck.class));
            return;
        }
        Path directory=Files.createTempDirectory("roster-real-jar-");
        try {
            Path jar=directory.resolve("test archive.jar"),outside=directory.resolve("outside.jar");archive(jar,false);archive(outside,true);String expected=digest(jar);
            System.out.println("Executable fixture JAR SHA-256 "+expected);
            launch(directory,jar,expected,List.of("-Xms16m","-Xmx64m"),true,"existing-memory-prefix");
            launch(directory,jar,expected,List.of("-Duser.timezone=UTC","-Xms128m","-Xmx1024m"),true,"actual-native-fixed-UTC-prefix");
            launch(directory,jar,expected,List.of("-Xms16m","-Duser.timezone=UTC","-Xmx64m"),true,"fixed-UTC-between-memory-options");
            String wrong=(expected.charAt(0)=='0'?"1":"0")+expected.substring(1);
            launch(directory,jar,wrong,List.of("-Duser.timezone=UTC","-Xms128m","-Xmx1024m"),false,"same-native-prefix-wrong-SHA");
            for(String option:List.of("-Duser.timezone=GMT","-Duser.timezone=Etc/UTC","-Duser.timezone=utc","-Duser.timezone=UTC ","-Duser.timezone","-Duser.language=en","-Djava.class.path=other.jar"))
                launch(directory,jar,expected,List.of(option),false,"unreviewed-option-"+option);
            launch(directory,jar,expected,List.of("-Duser.timezone=UTC","-Duser.timezone=UTC"),false,"duplicate-UTC-option");
            launch(directory,jar,expected,List.of("-Duser.timezone=UTC","-Duser.timezone=GMT"),false,"overridden-timezone");
            launch(directory,jar,expected,List.of("-Duser.timezone=UTC"),false,"outside-code-source-anchor",outside.toString());
            String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
            execute(directory,List.of(Path.of(System.getProperty("java.home"),"bin",executable).toString(),"-Duser.timezone=UTC","-cp",jar.toString(),"RosterArtifactLaunchCheck","--verify-archive",expected),false,"classpath-launch");
        } finally {
            try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
        System.out.println("PASS roster executable fixture-JAR launch: "+checks+" assertions, "+launches+" actual child JVMs; native Spring fat-JAR/MySQL NOT_RUN");
    }
}
