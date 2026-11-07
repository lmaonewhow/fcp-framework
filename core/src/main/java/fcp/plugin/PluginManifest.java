package fcp.plugin;

import fcp.Json;
import fcp.Schema;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Version 1 package contract. Parsing declares requirements; it does not grant them.
 * Only local, trusted-code packages are supported. No command shell or environment is accepted.
 */
public record PluginManifest(String pluginId, String version, String publisher, String contractVersion,
        Runtime runtime, List<Dependency> dependencies, List<Capability> provides,
        List<Capability> requires, Map<String,Object> storage, Map<String,Object> ui,
        List<String> secretRefs, Map<String,Object> lifecycle) {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final String SEMVER = "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?";
    public record Runtime(String type, String entry, List<String> argv) {
        public Runtime { argv = List.copyOf(argv); }
    }
    public record Dependency(String pluginId, String versionRange) {}
    public record Capability(String id, String version, String permission, boolean sideEffects,
            boolean idempotent, int timeoutMillis, Map<String,Object> inputSchema, Map<String,Object> outputSchema) {
        public Capability {
            try {Schema.check(inputSchema); Schema.check(outputSchema);} catch(RuntimeException e) {throw invalid("Invalid capability schema");}
            inputSchema = immutable(inputSchema); outputSchema = immutable(outputSchema);
        }
    }
    public PluginManifest {
        dependencies = List.copyOf(dependencies); provides = List.copyOf(provides); requires = List.copyOf(requires);
        storage = immutable(storage); ui = immutable(ui); secretRefs = List.copyOf(secretRefs); lifecycle = immutable(lifecycle);
    }
    public static PluginManifest parse(String json) {
        if (json == null || json.length() > 1024 * 1024) throw invalid("Manifest size limit");
        Map<String,Object> m = object(Json.parse(json), "manifest");
        fields(m, "pluginId version manifestVersion publisher contractVersion runtime dependencies provides requires storage ui secretRefs lifecycle");
        if (integer(m, "manifestVersion", 1, 1) != 1) throw invalid("Unsupported manifest version");
        String id = id(m, "pluginId"), version = version(string(m,"version"));
        String publisher = id(m, "publisher"), contract = string(m,"contractVersion");
        if (!contract.equals("1.0")) throw invalid("Unsupported contract version");
        Map<String,Object> r = object(m.get("runtime"), "runtime"); fields(r,"type entry argv");
        if (!string(r,"type").equals("local")) throw invalid("Only local runtime supported");
        String entry = relativePath(string(r,"entry"));
        List<String> args = strings(r.get("argv"), "argv", 128);
        for (String arg : args) if (arg.length() > 4096 || arg.indexOf('\0') >= 0 || arg.indexOf('\n') >= 0 || arg.indexOf('\r') >= 0)
            throw invalid("Invalid runtime argument");
        List<Dependency> deps = new ArrayList<>(); Set<String> depIds = new HashSet<>();
        for (Object item : array(m.getOrDefault("dependencies",List.of()),"dependencies",128)) {
            Map<String,Object> d = object(item,"dependency"); fields(d,"pluginId versionRange");
            String dep = id(d,"pluginId"), range = string(d,"versionRange");
            if (dep.equals(id) || !depIds.add(dep)) throw invalid("Self or duplicate dependency");
            validateRange(range); deps.add(new Dependency(dep,range));
        }
        Map<String,Object> storage = object(m.getOrDefault("storage",Map.of()),"storage"); validateStorage(storage);
        Map<String,Object> ui = object(m.getOrDefault("ui",Map.of()),"ui"); validateUi(ui);
        List<String> secrets = strings(m.getOrDefault("secretRefs",List.of()),"secretRefs",128);
        Set<String> names = new HashSet<>(); for (String ref : secrets) if (!ID.matcher(ref).matches() || !names.add(ref)) throw invalid("Invalid secret reference name");
        Map<String,Object> life = object(m.getOrDefault("lifecycle",Map.of()),"lifecycle");
        fields(life,"drainable cancellable upgradeMode healthOperation readyOperation");
        for(String b:List.of("drainable","cancellable")) if(life.containsKey(b)) bool(life,b);
        if (life.containsKey("upgradeMode") && !Set.of("stop-start","compatible").contains(string(life,"upgradeMode"))) throw invalid("Unsupported upgrade mode");
        for(String key:List.of("healthOperation","readyOperation")) if(life.containsKey(key)) id(life,key);
        return new PluginManifest(id,version,publisher,contract,new Runtime("local",entry,args),deps,
                capabilities(m.getOrDefault("provides",List.of())),capabilities(m.getOrDefault("requires",List.of())),storage,ui,secrets,life);
    }
    private static List<Capability> capabilities(Object raw) {
        List<Capability> result = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (Object v : array(raw,"capabilities",256)) {
            Map<String,Object> c=object(v,"capability"); fields(c,"id version permission sideEffects idempotent timeoutMillis inputSchema outputSchema");
            String id=id(c,"id"); if(!ids.add(id)) throw invalid("Duplicate capability");
            result.add(new Capability(id,version(string(c,"version")),id(c,"permission"),bool(c,"sideEffects"),bool(c,"idempotent"),
                    integer(c,"timeoutMillis",1,300000),object(c.get("inputSchema"),"inputSchema"),object(c.get("outputSchema"),"outputSchema")));
        } return result;
    }
    private static void validateStorage(Map<String,Object> s) {
        fields(s,"schemaVersion compatibleVersions tables migrations"); if(s.isEmpty()) return;
        integer(s,"schemaVersion",1,1000000);
        if(s.containsKey("compatibleVersions")) for(Object n:array(s.get("compatibleVersions"),"compatibleVersions",128)) {
            if(!(n instanceof BigDecimal d) || d.scale()>0 || d.signum()<1) throw invalid("Invalid compatible schema version");
        }
        Set<String> tables = new HashSet<>(); Map<String,Set<String>> nullableFields = new HashMap<>();
        for(Object t:array(s.getOrDefault("tables",List.of()),"tables",128)) {
            Map<String,Object> table=object(t,"table"); fields(table,"name fields indexes");
            if(!tables.add(id(table,"name"))) throw invalid("Duplicate table");
            Set<String> columns=new HashSet<>(); Set<String> nullable=new HashSet<>(); nullableFields.put(id(table,"name"),nullable);
            for(Object f:array(table.get("fields"),"fields",256)) {
                Map<String,Object> field=object(f,"field"); fields(field,"name type nullable maxLength");
                if(!columns.add(id(field,"name"))) throw invalid("Duplicate field");
                if(!Set.of("string","integer","decimal","boolean","timestamp","json").contains(string(field,"type"))) throw invalid("Unsupported field type");
                if(bool(field,"nullable")) nullable.add(id(field,"name")); if(field.containsKey("maxLength")) integer(field,"maxLength",1,1048576);
            }
            Set<String> indexes=new HashSet<>();
            for(Object i:array(table.getOrDefault("indexes",List.of()),"indexes",128)) {
                Map<String,Object> index=object(i,"index"); fields(index,"name fields unique");
                if(!indexes.add(id(index,"name"))) throw invalid("Duplicate index"); bool(index,"unique");
                List<String> fs=strings(index.get("fields"),"index fields",32);
                if(fs.isEmpty() || new HashSet<>(fs).size()!=fs.size() || !columns.containsAll(fs)) throw invalid("Invalid index fields");
            }
        }
        // Execution of migrations is a separate control-plane action; arbitrary SQL is never accepted.
        for(Object x:array(s.getOrDefault("migrations",List.of()),"migrations",128)) {
            Map<String,Object> migration=object(x,"migration"); fields(migration,"from to operations");
            int from=integer(migration,"from",0,1000000),to=integer(migration,"to",1,1000000);
            if(to!=from+1) throw invalid("Migration must be incremental");
            for(Object op:array(migration.get("operations"),"operations",256)) {
                Map<String,Object> operation=object(op,"operation"); fields(operation,"type table field");
                if(!string(operation,"type").equals("addField")) throw invalid("Unsupported migration operation");
                if(!tables.contains(id(operation,"table"))) throw invalid("Unknown migration table");
                if(!nullableFields.get(id(operation,"table")).contains(id(operation,"field"))) throw invalid("Added field must be declared and nullable");
            }
        }
    }
    private static void validateUi(Map<String,Object> ui) {
        fields(ui,"contractVersion pages slots"); if(ui.isEmpty()) return;
        if(!string(ui,"contractVersion").equals("1.0")) throw invalid("Unsupported UI contract");
        Set<String> ids=new HashSet<>();
        for(String kind:List.of("pages","slots")) {
            for(Object item:array(ui.getOrDefault(kind,List.of()),kind,128)) {
                Map<String,Object> node=object(item,"UI entry");
                fields(node,kind.equals("pages")?"id title permissions components capabilities content":"id slotId order permissions components capabilities content");
                if(!ids.add(id(node,"id"))) throw invalid("Duplicate UI identifier");
                if(kind.equals("pages")) uiText(node,"title"); else {id(node,"slotId");integer(node,"order",-1000000,1000000);}
                Set<String> permissions=identifierList(node.get("permissions"),"permissions");
                Set<String> capabilities=identifierList(node.get("capabilities"),"capabilities");
                List<String> components=strings(node.get("components"),"components",32);
                Set<String> known=Set.of("text@1","field@1","button@1","table@1","stack@1");
                if(!known.containsAll(components) || new HashSet<>(components).size()!=components.size()) throw invalid("Unknown/duplicate UI component");
                validateNode(object(node.get("content"),"content"),new HashSet<>(components),capabilities,0,new int[]{0});
            }
        }
    }
    private static String uiText(Map<String,Object> object,String key) {
        String text=string(object,key);
        if(text.length()>2048 || text.contains("<") || text.contains(">") || text.contains("${") || text.contains("{{")) throw invalid("UI text must be a bounded plain literal");
        return text;
    }
    private static Set<String> identifierList(Object raw,String name) {
        Set<String> result=new HashSet<>();for(String s:strings(raw,name,256)) if(s.length()>128 || !ID.matcher(s).matches() || !result.add(s)) throw invalid("Invalid/duplicate "+name);return result;
    }
    private static void validateNode(Map<String,Object> node,Set<String> components,Set<String> capabilities,int depth,int[] count) {
        if(depth>13 || ++count[0]>1000) throw invalid("UI node limit");
        fields(node,"type text field capabilityId children");String type=string(node,"type");
        if(!components.contains(type+"@1")) throw invalid("Undeclared UI component");
        if(node.containsKey("text")) uiText(node,"text");
        if(node.containsKey("field")) id(node,"field");
        if(node.containsKey("capabilityId") && (!type.equals("button") || !capabilities.contains(id(node,"capabilityId")))) throw invalid("Undeclared UI action");
        if(node.containsKey("children")) {
            if(!type.equals("stack")) throw invalid("Only stack may have children");
            for(Object child:array(node.get("children"),"children",128)) validateNode(object(child,"child"),components,capabilities,depth+1,count);
        }
    }
    /** Deliberately small range grammar: exact stable version or caret with same nonzero major. */
    public static void validateRange(String range) {
        if(range.startsWith("^")) { version(range.substring(1)); if(range.startsWith("^0.") || range.contains("-")) throw invalid("Unsupported caret range"); }
        else version(range);
    }
    public static boolean matchesVersion(String actual,String range) {
        version(actual); validateRange(range); if(!range.startsWith("^")) return actual.equals(range);
        if(actual.contains("-")) return false;
        String[] a=actual.split("\\."),b=range.substring(1).split("\\.");
        if(!a[0].equals(b[0])) return false;
        for(int i=1;i<3;i++) { int cmp=new java.math.BigInteger(a[i]).compareTo(new java.math.BigInteger(b[i])); if(cmp!=0) return cmp>0; } return true;
    }
    public static String relativePath(String path) {
        if(path.isEmpty() || path.length()>240 || path.startsWith("/") || path.indexOf('\\')>=0 || path.indexOf(':')>=0) throw invalid("Invalid package path");
        for(String part:path.split("/",-1)) if(part.isEmpty() || part.equals(".") || part.equals("..") || !part.matches("[A-Za-z0-9_@.-]+")) throw invalid("Invalid package path");
        return path;
    }
    private static String version(String s) { if(s.length()>100 || !s.matches(SEMVER)) throw invalid("Invalid semantic version"); return s; }
    private static String id(Map<String,Object> m,String k) { String s=string(m,k); if(s.length()>128 || !ID.matcher(s).matches()) throw invalid("Invalid identifier: "+k); return s; }
    private static String string(Map<String,Object> m,String k) { if(!(m.get(k) instanceof String s) || s.isBlank() || s.length()>4096) throw invalid("Expected string: "+k); return s; }
    private static boolean bool(Map<String,Object> m,String k) { if(!(m.get(k) instanceof Boolean b)) throw invalid("Expected boolean: "+k); return b; }
    private static int integer(Map<String,Object> m,String k,int min,int max) {
        if(!(m.get(k) instanceof BigDecimal d)) throw invalid("Expected integer: "+k);
        try {int n=d.intValueExact(); if(n<min || n>max) throw invalid("Integer out of range: "+k); return n;} catch(ArithmeticException e) {throw invalid("Expected integer: "+k);}
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object o,String label) { if(!(o instanceof Map<?,?>)) throw invalid("Expected object: "+label); return (Map<String,Object>)o; }
    private static List<?> array(Object o,String label,int max) { if(!(o instanceof List<?> a) || a.size()>max) throw invalid("Expected bounded array: "+label); return a; }
    private static List<String> strings(Object o,String label,int max) { List<String> result=new ArrayList<>(); for(Object v:array(o,label,max)) {if(!(v instanceof String s))throw invalid("Expected string array: "+label);result.add(s);} return result; }
    private static void fields(Map<String,Object> map,String names) { Set<String> allowed=Set.of(names.split(" ")); for(String key:map.keySet()) if(!allowed.contains(key)) throw invalid("Unknown field: "+key); }
    @SuppressWarnings("unchecked") private static <T> T freeze(T value) {
        if(value instanceof Map<?,?> m) { Map<String,Object> out=new LinkedHashMap<>();m.forEach((k,v)->out.put((String)k,freeze(v)));return (T)Collections.unmodifiableMap(out); }
        if(value instanceof List<?> l) return (T)Collections.unmodifiableList(l.stream().map(PluginManifest::freeze).toList()); return value;
    }
    private static Map<String,Object> immutable(Map<String,Object> m) {return freeze(m);}
    private static IllegalArgumentException invalid(String message) {return new IllegalArgumentException(message);}
}
