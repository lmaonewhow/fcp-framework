package fcp;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static fcp.Schema.*;

/** Administrator-owned registry. Module descriptors use exact contract identities. */
public final class Registry {
    public final Map<String,Map<String,Object>> contracts=new LinkedHashMap<>(),modules=new LinkedHashMap<>(),compositions=new LinkedHashMap<>();
    public final Map<String,Engine.Factory> factories=new HashMap<>();
    public static Registry load(Path root)throws IOException{
        Registry registry=new Registry();registry.loadDirectory(root.resolve("contracts"),"contract");registry.loadDirectory(root.resolve("modules"),"module");registry.loadDirectory(root.resolve("compositions"),"composition");return registry;
    }
    private void loadDirectory(Path directory,String kind)throws IOException{
        try(var paths=Files.list(directory)){for(Path path:paths.filter(p->p.toString().endsWith(".json")).sorted().toList())register(kind,map(Json.parse(Files.readString(path))));}
    }
    public synchronized void register(String kind,Map<String,Object> document){
        Map<String,Object> doc=map(cloneJson(document));ident(doc.get("id"));String id=str(doc.get("id"));
        Map<String,Map<String,Object>> target=switch(kind){case "contract"->contracts;case "module"->modules;case "composition"->compositions;default->throw new FcpException("MANIFEST",kind);};
        if(target.containsKey(id))throw new FcpException("DUPLICATE",id);
        if(kind.equals("contract")){
            if(obj(doc,"operations").isEmpty())throw new FcpException("SCHEMA","Operations required");
            for(var entry:obj(doc,"operations").entrySet()){ident(entry.getKey());Map<String,Object> operation=map(entry.getValue());Schema.check(map(operation.get("input")));Schema.check(map(operation.get("output")));if(!Set.of("read","write","external").contains(operation.getOrDefault("effect","read")))throw new FcpException("SCHEMA","Unknown effect");}
        }else if(!"fcp/0.3".equals(doc.get("protocol"))||!kind.equals(doc.get("kind")))throw new FcpException("VERSION","Unsupported "+kind);
        if(kind.equals("module")){
            for(String key:doc.keySet())if(!Set.of("protocol","kind","id","version","title","description","provides","requires","runtime","config","ui").contains(key))throw new FcpException("MANIFEST","Unknown module field: "+key);
            if(!(doc.get("version") instanceof String)||obj(doc,"provides").isEmpty())throw new FcpException("MANIFEST","Version and exports required");
            for(var entry:obj(doc,"provides").entrySet()){ident(entry.getKey());if(!contracts.containsKey(entry.getValue()))throw new FcpException("CONTRACT_MISSING",entry.getValue().toString());}
            for(var entry:obj(doc,"requires").entrySet()){ident(entry.getKey());if(!contracts.containsKey(map(entry.getValue()).get("contract")))throw new FcpException("CONTRACT_MISSING",entry.getKey());}
        }
        target.put(id,doc);
    }
    public Map<String,Object> expand(Object app){
        Map<String,Object> source=app instanceof String?compositions.get(app):map(app);
        if(source==null)throw new FcpException("COMPOSITION_MISSING",String.valueOf(app));
        if(!"fcp/0.3".equals(source.get("protocol"))||!"composition".equals(source.get("kind")))throw new FcpException("VERSION","Unsupported composition");
        for(String key:source.keySet())if(!Set.of("protocol","kind","id","title","includes","modules","bindings","exports","ui","profile").contains(key))throw new FcpException("MANIFEST","Unknown composition field: "+key);
        Map<String,Object> result=Json.obj("modules",new ArrayList<>(),"bindings",new LinkedHashMap<>(),"exports",new LinkedHashMap<>(),"ui",new ArrayList<>(),"profile",new LinkedHashMap<>());
        visit(source,result,new HashSet<>(),new LinkedHashSet<>());result.put("id",source.get("id"));result.put("title",source.getOrDefault("title",source.get("id")));return map(cloneJson(result));
    }
    private void visit(Map<String,Object> source,Map<String,Object> result,Set<String> seen,Set<String> stack){
        String id=str(source.get("id"));if(stack.contains(id))throw new FcpException("COMPOSITION_CYCLE",id);if(seen.contains(id))return;stack.add(id);
        for(Object include:arr(source,"includes")){Map<String,Object> child=compositions.get(include);if(child==null)throw new FcpException("COMPOSITION_MISSING",include.toString());visit(child,result,seen,stack);}
        stack.remove(id);seen.add(id);
        for(Object module:arr(source,"modules"))if(!arr(result,"modules").contains(module))arr(result,"modules").add(module);
        for(String section:List.of("bindings","exports","profile")){Map<String,Object> output=obj(result,section);for(var entry:obj(source,section).entrySet()){if(output.containsKey(entry.getKey())&&!output.get(entry.getKey()).equals(entry.getValue()))throw new FcpException("BINDING_CONFLICT",entry.getKey());output.put(entry.getKey(),entry.getValue());}}
        arr(result,"ui").addAll(arr(source,"ui"));
    }
}
