package fcp.plugin;

import fcp.FcpException;
import fcp.Json;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** First-phase trusted-code dependency gate, NOT an OS sandbox or a malicious-code verifier.
 * Checks declarative package dependencies and literal direct imports before publication. No package
 * manager, build script, network lookup or plugin code is executed. Dynamic/obfuscated imports and
 * the contents of third-party registry packages still require publisher review.
 */
public final class PluginDependencyPolicy {
    private PluginDependencyPolicy() {}
    private static final Pattern PYTHON_IMPORT=Pattern.compile("(?m)(?:^|[;:])\\s*(?:from\\s+([\\w.]+)\\s+import\\s+(\\([^)]*\\)|[^;\\n#]+)|import\\s+([^;\\n#]+))");
    private static final Pattern JS_IMPORT=Pattern.compile("(?:\\b(?:import|export)\\s+(?:[^;]*?\\s+from\\s*)?|\\b(?:require|import)\\s*\\(\\s*)['\"]([^'\"]+)['\"]");
    private static final Pattern REQUIREMENT=Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]*)(?:\\[[A-Za-z0-9_,.-]+\\])?\\s*(?:[<>=!~].*)?$");
    private static final Set<String> UNSUPPORTED=Set.of("pyproject.toml","setup.py","setup.cfg","pipfile","pipfile.lock","poetry.lock","uv.lock","requirements.in","pom.xml","build.gradle","build.gradle.kts","npm-shrinkwrap.json","yarn.lock","pnpm-lock.yaml");
    public static void verify(Path root) throws IOException {
        try(var paths=Files.walk(root)) {
            for(Path path:paths.sorted().toList()) {
                if(Files.isSymbolicLink(path))throw invalid("Symlink in package dependency tree");
                if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))continue;
                String relative=root.relativize(path).toString().replace('\\','/'),name=path.getFileName().toString().toLowerCase(Locale.ROOT);
                if(name.endsWith(".jar")||name.endsWith(".class"))throw invalid("JVM implementation artifacts are not supported in Python/Node packages");
                if(UNSUPPORTED.contains(name)||name.startsWith("requirements")&&name.endsWith(".txt")&&!name.equals("requirements.txt"))throw metadata("Unsupported dependency metadata: "+relative+"; use requirements.txt or package.json");
                if(!(name.equals("requirements.txt")||name.equals("package.json")||name.equals("package-lock.json")||name.endsWith(".py")||name.endsWith(".js")||name.endsWith(".mjs")||name.endsWith(".cjs")||name.endsWith(".ts")))continue;
                if(Files.size(path)>2*1024*1024)throw metadata("Dependency source exceeds first-phase inspection limit: "+relative);
                String source=Files.readString(path);
                if(name.equals("requirements.txt"))requirements(source,relative);
                else if(name.equals("package.json")||name.equals("package-lock.json"))nodeMetadata(source,relative);
                else if(name.endsWith(".py")) {
                    Matcher imports=PYTHON_IMPORT.matcher(source);
                    while(imports.find()) {
                        if(imports.group(1)!=null) {
                            dependency(imports.group(1),relative);
                            for(String item:imports.group(2).replaceAll("(?m)#.*$", "").replace("(", "").replace(")", "").split(",")) {
                                String member=item.strip().split("\\s+",2)[0];if(!member.isEmpty())dependency(imports.group(1)+"."+member,relative);
                            }
                        } else for(String item:imports.group(3).split(","))dependency(item.strip().split("\\s+",2)[0],relative);
                    }
                } else {
                    Matcher imports=JS_IMPORT.matcher(source);while(imports.find())dependency(imports.group(1),relative);
                }
            }
        }
    }
    private static void requirements(String source,String file) {
        for(String raw:source.split("\\R")) {
            String line=raw.split("#",2)[0].strip();if(line.isEmpty())continue;
            // Includes, editables, URL/path requirements and build flags can conceal implementation
            // dependencies. First-phase packages must carry one explicit, static registry list.
            Matcher match=REQUIREMENT.matcher(line);
            if(!match.matches()||line.contains(" @ ")||line.contains(";")||line.contains("\\"))throw metadata("Unsupported requirement in "+file+"; use explicit registry requirements");
            dependency(match.group(1),file);
        }
    }
    @SuppressWarnings("unchecked") private static void nodeMetadata(String source,String file) {
        Object parsed;
        try{parsed=Json.parse(source);}catch(RuntimeException error){throw metadata("Invalid JSON dependency metadata: "+file);}
        if(!(parsed instanceof Map<?,?> map))throw metadata("Dependency metadata must be an object: "+file);
        walkNode((Map<String,Object>)map,file);
    }
    private static void walkNode(Map<String,Object> map,String file) {
        if(map.get("name") instanceof String name)dependency(name,file);
        for(String kind:List.of("bundledDependencies","bundleDependencies")) {
            Object raw=map.get(kind);if(raw==null||raw instanceof Boolean)continue;
            if(!(raw instanceof List<?> dependencies))throw metadata("Invalid "+kind+" in "+file);
            for(Object name:dependencies) {
                if(!(name instanceof String value))throw metadata("Invalid bundled dependency in "+file);
                dependency(value,file);
            }
        }
        // package-lock v1 uses nested dependencies; v2/v3 uses packages[path].
        for(String kind:List.of("dependencies","devDependencies","optionalDependencies","peerDependencies")) {
            Object raw=map.get(kind);if(raw==null)continue;
            if(!(raw instanceof Map<?,?> deps))throw metadata("Invalid "+kind+" in "+file);
            for(var entry:deps.entrySet()) {
                dependency(entry.getKey().toString(),file);
                if(entry.getValue() instanceof String spec)specifier(spec,file);
                else if(entry.getValue() instanceof Map<?,?> info) {
                    Object version=info.get("version");if(version instanceof String spec)specifier(spec,file);
                    @SuppressWarnings("unchecked") Map<String,Object> nested=(Map<String,Object>)info;walkNode(nested,file);
                } else throw metadata("Invalid dependency declaration in "+file);
            }
        }
        Object packages=map.get("packages");
        if(packages instanceof Map<?,?> entries)for(var entry:entries.entrySet()) {
            String location=entry.getKey().toString();int index=location.lastIndexOf("node_modules/");
            if(index>=0)dependency(location.substring(index+13),file);
            if(entry.getValue() instanceof Map<?,?> info) {
                if(Boolean.TRUE.equals(info.get("link")))throw metadata("Local linked dependency in "+file);
                if(info.get("name") instanceof String name)dependency(name,file);
                @SuppressWarnings("unchecked") Map<String,Object> nested=(Map<String,Object>)info;walkNode(nested,file);
            }
        }
    }
    private static void specifier(String spec,String file) {
        if(spec.startsWith("npm:")) {
            String alias=spec.substring(4);int version=alias.indexOf('@',1);dependency(version<0?alias:alias.substring(0,version),file);return;
        }
        if(spec.contains(":")||spec.startsWith(".")||spec.startsWith("/")||spec.contains("\\")||spec.contains("/"))throw metadata("Only registry dependencies are supported in "+file);
    }
    private static void dependency(String name,String file) {
        String value=name.toLowerCase(Locale.ROOT).replace('_','-').replace('\\','/');
        String path=value.replaceFirst("^(?:\\.\\.?/)+", "");
        if(path.startsWith("host/src/")||path.startsWith("business/src/")||path.startsWith("integration/ruoyi/")||path.startsWith("core/src/"))throw invalid("Direct host source dependency in "+file+": "+name);
        boolean blocked=value.equals("ruoyi")||value.startsWith("ruoyi-")||value.startsWith("ruoyi.")||value.startsWith("@ruoyi/")
            ||value.equals("org.dromara")||value.startsWith("org.dromara.")||value.equals("com.ruoyi")||value.startsWith("com.ruoyi.")||value.startsWith("org/dromara/")||value.startsWith("com/ruoyi/")
            ||value.equals("@fcp/host")||value.startsWith("@fcp/host/")||value.equals("@fcp/business")||value.startsWith("@fcp/business/")||value.equals("@fcp/core")||value.startsWith("@fcp/core/")
            ||value.matches("fcp[.-](host|business|core)(?:[./-].*)?")||value.matches("fcp\\.(runtime|control|engine|registry)(?:\\..*)?")||value.matches("dev\\.fcp\\.(host|school|dual|ingestion)(?:\\..*)?");
        if(blocked)throw invalid("Direct host implementation dependency in "+file+": "+name);
    }
    private static FcpException invalid(String message){return new FcpException("HOST_IMPLEMENTATION_DEPENDENCY",message);}
    private static FcpException metadata(String message){return new FcpException("PLUGIN_DEPENDENCY_METADATA",message);}
    public static void main(String[] args)throws Exception {
        if(args.length!=1||!Files.isDirectory(Path.of(args[0])))throw new IllegalArgumentException("Usage: PluginDependencyPolicy <unpacked-plugin-directory>");
        verify(Path.of(args[0]));System.out.println("PASS: first-phase declared dependency policy (trusted code; not a sandbox)");
    }
}
