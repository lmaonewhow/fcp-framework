import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Build the framework-only library using the installed JDK; no Maven or network required. */
public class Build {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args.length==0?".":args[0]).toAbsolutePath().normalize();
        var compiler=ToolProvider.getSystemJavaCompiler();
        if(compiler==null)throw new IllegalStateException("A JDK 17+ is required for source compilation");
        Path build=root.resolve("build"),dist=root.resolve("dist");Files.createDirectories(build);Files.createDirectories(dist);
        Path classes=Files.createTempDirectory(build,"classes-");
        try {
            List<String> options=new ArrayList<>(List.of("--release","17","-encoding","UTF-8","-d",classes.toString()));
            try(var files=Files.walk(root.resolve("src/main/java"))){options.addAll(files.filter(p->p.toString().endsWith(".java")).sorted().map(Path::toString).toList());}
            if(compiler.run(null,System.out,System.err,options.toArray(String[]::new))!=0)throw new IllegalStateException("Framework compilation failed");
            Path target=dist.resolve("fcp-core-0.5.0-dev.1.jar");
            Manifest manifest=new Manifest();manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,"1.0");
            manifest.getMainAttributes().putValue("Implementation-Title","FCP framework library");manifest.getMainAttributes().putValue("Implementation-Version","0.5.0-dev.1");
            try(var output=new JarOutputStream(Files.newOutputStream(target),manifest);var files=Files.walk(classes)){
                for(Path file:files.filter(Files::isRegularFile).sorted().toList()){
                    JarEntry entry=new JarEntry(classes.relativize(file).toString().replace('\\','/'));entry.setTime(0);output.putNextEntry(entry);Files.copy(file,output);output.closeEntry();
                }
            }
            System.out.println("Built framework library: "+target);
        } finally {try(var files=Files.walk(classes)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
