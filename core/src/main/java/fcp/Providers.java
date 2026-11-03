package fcp;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.function.BiPredicate;
import static fcp.Schema.*;

/** Optional reference modules. Real hosts can register their own service factories. */
public final class Providers {
    private Providers(){}
    public static Engine bootstrap(Path root,boolean protectedHost,BiPredicate<Engine.Context,Map<String,Object>> policy)throws IOException{
        Registry registry=Registry.load(root);Map<String,Object> launchers=map(Json.parse(Files.readString(root.resolve("apps/launchers.json"))));
        registry.factories.put("stdio",(document,scope)->new ProcessProvider(document,root,launchers));
        registry.factories.put("memory-records",(document,scope)->new Records(null));
        registry.factories.put("json-records",(document,scope)->new Records(root.resolve("var/java-records").resolve(scope+".json")));
        registry.factories.put("workspace",(document,scope)->new Workspace(obj(document,"config")));
        registry.factories.put("device-rules",(document,scope)->(ctx,ex,op,input)->{String name=trim(str(input.get("name"))),code=trim(str(input.get("code")));if(name.isEmpty()||code.isEmpty())throw new FcpException("INVALID_INPUT","Empty device");return Json.obj("code",code,"name",name,"protocol",input.get("protocol"),"enabled",input.get("enabled"));});
        registry.factories.put("text-tools",(document,scope)->(ctx,ex,op,input)->{String text=trim(str(input.get("text")));return Json.obj("text",text,"characters",text.codePointCount(0,text.length()));});
        registry.factories.put("dashboard",(document,scope)->(ctx,ex,op,input)->Json.obj("projects",arr(map(ctx.call("projects","list",Map.of())),"items").size(),"devices",arr(map(ctx.call("devices","list",Map.of())),"items").size()));
        registry.factories.put("local-policy",(document,scope)->(ctx,ex,op,input)->{Object role=ctx.attributes.get("role");boolean allowed="admin".equals(role)||"reader".equals(role)&&"read".equals(input.get("effect"));boolean readonly="readonly".equals(obj(document,"config").get("mode"));if(readonly&&!"read".equals(input.get("effect")))allowed=false;return Json.obj("allowed",allowed,"provider",readonly?"readonly":"local");});
        if(policy!=null)registry.factories.put("host-policy",(document,scope)->(ctx,ex,op,input)->Json.obj("allowed",policy.test(ctx,input),"provider","host-callback"));
        Engine.Guard guard=protectedHost?(ctx,target,operation)->{String ref=(String)ctx.profile().get("policy");if(ref==null)throw new FcpException("POLICY_MISSING","Policy binding required by this host");if(ref.equals(target))throw new FcpException("FORBIDDEN","Policy bootstrap is host-owned");Map<String,Object> decision=map(ctx.policy(ref,Json.obj("target",target,"operation",operation,"effect",ctx.effect(target,operation))));if(!Boolean.TRUE.equals(decision.get("allowed")))throw new FcpException("FORBIDDEN","Host policy denied");}:null;
        return new Engine(registry,guard,ui->{if(!Set.of("records","operation-form").contains(ui.get("component")))throw new FcpException("UI_CATALOG","Reference host component unavailable");});
    }
    private static String trim(String input){return input.replaceAll("^[ \\t\\r\\n]+|[ \\t\\r\\n]+$","");}
    private static final class Workspace implements Engine.Provider{
        final Map<String,Object> config;Workspace(Map<String,Object> config){this.config=config;}
        public Object invoke(Engine.Context context,String export,String operation,Map<String,Object> input){String collection=str(config.get("collection"));if(operation.equals("list"))return context.call("records","list",Json.obj("collection",collection));Map<String,Object> value=map(context.call("rules","normalize",map(input.get("value"))));if(operation.equals("preview"))return Json.obj("normalized",value);return context.call("records","put",Json.obj("collection",collection,"key",value.get(config.get("key")),"value",value,"expectedVersion",input.get("expectedVersion"),"requestKey",input.get("requestKey")));}
    }
    private static Object canonical(Object value){if(value instanceof Map){Map<String,Object> result=new TreeMap<>();for(var e:map(value).entrySet())result.put(e.getKey(),canonical(e.getValue()));return result;}if(value instanceof List<?> list)return list.stream().map(Providers::canonical).toList();return value;}
    public static final class Records implements Engine.Provider{
        private Map<String,Object> rows=new TreeMap<>(),requests=new TreeMap<>();private final Path path;
        public Records(Path path){this.path=path;if(path!=null&&Files.exists(path))try{Map<String,Object> data=map(Json.parse(Files.readString(path)));rows=obj(data,"rows");requests=obj(data,"requests");}catch(IOException error){throw new FcpException("STORAGE",error.toString(),error);}}
        public Object invoke(Engine.Context context,String export,String operation,Map<String,Object> input){
            String collection=str(input.get("collection"));
            if(operation.equals("list"))return context.atomic(()->{List<Object> values=new ArrayList<>();for(var entry:rows.entrySet())if(entry.getKey().startsWith(collection+":"))values.add(cloneJson(entry.getValue()));return Json.obj("items",values);});
            if(!operation.equals("put"))throw new FcpException("OPERATION_MISSING",operation);
            return context.atomic(()->{
                String key=collection+":"+str(input.get("key"));String request=Json.write(List.of(context.attributes.getOrDefault("subject",""),collection,input.get("requestKey")));Map<String,Object> data=new TreeMap<>(input);data.remove("requestKey");String fingerprint=Json.write(canonical(data));
                if(requests.containsKey(request)){Map<String,Object> cached=map(requests.get(request));if(!fingerprint.equals(cached.get("fingerprint")))throw new FcpException("IDEMPOTENCY_CONFLICT","Key already used with other content");return cached.get("result");}
                long expected=number(input.get("expectedVersion")),version=rows.containsKey(key)?number(map(rows.get(key)).get("version")):0;if(expected!=version)throw new FcpException("RECORD_CONFLICT","Record version conflict");
                Map<String,Object> result=Json.obj("key",input.get("key"),"version",version+1,"data",input.get("value"));Object previous=rows.put(key,result);requests.put(request,Json.obj("fingerprint",fingerprint,"result",result));
                try{persist();}catch(RuntimeException error){if(previous==null)rows.remove(key);else rows.put(key,previous);requests.remove(request);throw error;}return result;
            });
        }
        private void persist(){
            if(path==null)return;
            try{Files.createDirectories(path.getParent());Path temporary=Files.createTempFile(path.getParent(),"fcp-",".tmp");try{Files.writeString(temporary,Json.write(Json.obj("rows",rows,"requests",requests)));Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}}
            catch(IOException error){throw new FcpException("STORAGE",error.toString(),error);}
        }
    }
}
