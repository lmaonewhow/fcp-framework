package dev.fcp.ingestion;

import dev.fcp.dual.HostRequest;
import dev.fcp.dual.RecordStore;
import dev.fcp.school.SchoolFacade.Identity;
import fcp.*;
import fcp.state.PluginStateStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import static fcp.Schema.*;

/** Single-node, trusted-plugin ingestion. Only the authenticated host may commit data.
 * A stop drains admitted imports under a write lease, then disposes affected children.
 * Desired state survives restart. External API reads are never a database transaction.
 */
public final class IngestionService implements AutoCloseable {
    private final Engine engine;
    private final RecordStore records;
    private final PluginStateStore states;
    private final Consumer<Identity> reauthorize;
    private final BiFunction<String,String,Map<String,Object>> connections;
    private final Map<String,Map<String,Object>> plugins=new LinkedHashMap<>();
    private final Map<String,String> businesses=new LinkedHashMap<>();
    private final Map<String,Map<String,PluginStateStore.State>> applied=new HashMap<>();
    private final ReentrantReadWriteLock lifecycle=new ReentrantReadWriteLock(true);
    private boolean closed;

    public IngestionService(Path root,RecordStore records,PluginStateStore states,
            Consumer<Identity> reauthorize,BiFunction<String,String,Map<String,Object>> connections)throws IOException {
        this.records=Objects.requireNonNull(records);this.states=Objects.requireNonNull(states);
        this.reauthorize=Objects.requireNonNull(reauthorize);this.connections=Objects.requireNonNull(connections);
        Map<String,Object> catalog=map(Json.parse(Files.readString(root.resolve("ingestion/catalog.json"))));
        if(!"fcp-ingestion/1".equals(catalog.get("protocol")))throw new FcpException("MANIFEST","Unsupported ingestion catalog");
        Registry registry=new Registry();Map<String,Object> launchers=new LinkedHashMap<>();
        for(Object contract:arr(catalog,"contracts"))registry.register("contract",map(contract));
        for(Object item:arr(catalog,"plugins")) {
            Map<String,Object> p=map(cloneJson(item));String id=str(p.get("id")),entity=str(p.get("entity")),kind=str(p.get("kind"));
            ident(id);ident(entity);
            if(!Set.of("business","connector","adapter").contains(kind)||entity.length()>32||plugins.putIfAbsent(id,p)!=null)
                throw new FcpException("MANIFEST","Invalid or duplicate ingestion plugin");
            if(!id.equals(obj(p,"manifest").get("id")))throw new FcpException("MANIFEST","Plugin ID mismatch");
            registry.register("module",obj(p,"manifest"));launchers.put(id,obj(p,"launcher"));
            if(kind.equals("business")) {
                if(businesses.putIfAbsent(entity,id)!=null||arr(p,"keyFields").isEmpty())throw new FcpException("MANIFEST","One business plugin with key fields is required per entity");
                for(Object field:arr(p,"keyFields"))str(field);
            }
        }
        if(plugins.isEmpty()||plugins.size()>32)throw new FcpException("MANIFEST","Expected 1..32 installed ingestion plugins");
        for(Map<String,Object> p:plugins.values())if(!businesses.containsKey(p.get("entity")))throw new FcpException("MANIFEST","Missing business dependency");
        registry.factories.put("stdio",(manifest,scope)->new ProcessProvider(manifest,root,launchers));
        engine=new Engine(registry,(context,target,operation)->{
            Identity identity=new Identity(context.scope(),str(context.attributes.get("subject")),strings(arr(context.attributes,"permissions")),strings(arr(context.attributes,"classes")));
            reauthorize.accept(identity);
            String id=target.split("/",2)[0];
            long expected=number(obj(context.attributes,"revisions").get(id));
            PluginStateStore.State now=states.getOrCreate(key(identity.scope(),id),false);
            if(!now.enabled()||now.revision()!=expected)throw new FcpException("STALE_COMPOSITION","Plugin state changed during execution");
        });
    }
    private static Set<String> strings(List<Object> values){Set<String> result=new HashSet<>();for(Object value:values)result.add(str(value));return result;}
    private static PluginStateStore.Key key(String scope,String id){return new PluginStateStore.Key(scope,id);}
    private static void require(Identity identity,String permission){
        if(identity==null||identity.subject()==null||identity.subject().isBlank())throw new FcpException("UNAUTHENTICATED","Login required");
        if(!identity.permissions().contains("fcp:ingest:"+permission))throw new FcpException("FORBIDDEN","Ingestion permission is not granted");
    }
    private void live(){if(closed)throw new FcpException("CLOSED","Ingestion service closed");}
    private void classAccess(Identity identity,String classId){
        if(classId==null||!classId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))throw new FcpException("INVALID_INPUT","Invalid classId");
        if(!identity.permissions().contains("fcp:ingest:allClasses")&&!identity.classes().contains(classId))throw new FcpException("FORBIDDEN","Class is outside the authenticated teaching scope");
    }
    private Map<String,PluginStateStore.State> desired(String scope){
        Map<String,PluginStateStore.State> result=new LinkedHashMap<>();
        for(String id:plugins.keySet())result.put(id,states.getOrCreate(key(scope,id),false));return result;
    }
    private boolean effective(Map<String,PluginStateStore.State> state,String id){
        Map<String,Object> p=plugin(id);
        return state.get(id).enabled()&&state.get(businesses.get(str(p.get("entity")))).enabled();
    }
    private Map<String,Object> plugin(String id){Map<String,Object> p=plugins.get(id);if(p==null)throw new FcpException("NOT_FOUND","Plugin is not installed");return p;}
    private String business(String entity){String id=businesses.get(entity);if(id==null)throw new FcpException("NOT_FOUND","Unknown business entity");return id;}
    private void ensure(Identity identity){
        require(identity,"view");reauthorize.accept(identity);
        lifecycle.writeLock().lock();try{
            live();String scope=identity.scope();Map<String,PluginStateStore.State> old=applied.get(scope);
            if(old==null&&applied.size()>=128)throw new FcpException("RESOURCE_LIMIT","Active tenant capacity reached");
            Map<String,PluginStateStore.State> next=desired(scope);
            if(next.equals(old))return;
            List<String> enabled=new ArrayList<>();Map<String,Object> exports=new LinkedHashMap<>(),bindings=new LinkedHashMap<>();
            for(var entry:plugins.entrySet())if(effective(next,entry.getKey())) {
                String id=entry.getKey();enabled.add(id);exports.put(id,id+"/api");
                if(!"business".equals(entry.getValue().get("kind")))bindings.put(id+"/business",businesses.get(str(entry.getValue().get("entity")))+"/api");
            }
            long generation=old==null?0:number(engine.describe(scope).get("generation"));
            engine.publish(scope,Json.obj("protocol","fcp/0.3","kind","composition","id","ingestion.active","modules",enabled,"exports",exports,"bindings",bindings),generation);
            applied.put(scope,next);
        }finally{lifecycle.writeLock().unlock();}
    }
    private void current(Identity identity,Map<String,Object> request){
        live();HostRequest.checkCaller(request,identity.scope(),identity.subject());
        Map<String,Object> snapshot=engine.describe(identity.scope());
        if(!snapshot.get("instance").equals(request.get("instance"))||number(snapshot.get("generation"))!=number(request.get("generation")))
            throw new FcpException("STALE_COMPOSITION","Plugin catalog changed; refresh and retry");
    }
    private void available(Identity identity,String id){if(!effective(applied.get(identity.scope()),id))throw new FcpException("PLUGIN_DISABLED","Plugin or its business dependency is disabled");}
    private void fence(Identity identity){
        reauthorize.accept(identity);
        if(!desired(identity.scope()).equals(applied.get(identity.scope())))throw new FcpException("STALE_COMPOSITION","Plugin state changed before commit");
    }
    private Map<String,Object> invoke(Identity identity,String id,Map<String,Object> input){
        available(identity,id);Map<String,Object> snapshot=engine.describe(identity.scope());
        Map<String,Object> attrs=identity.attributes(),revisions=new LinkedHashMap<>();
        applied.get(identity.scope()).forEach((name,state)->revisions.put(name,state.revision()));attrs.put("revisions",revisions);
        return map(engine.invoke(identity.scope(),id,"run",input,attrs,number(snapshot.get("generation")),str(snapshot.get("instance"))));
    }
    public Map<String,Object> describe(Identity identity){
        ensure(identity);lifecycle.readLock().lock();try{
            live();Map<String,Object> raw=engine.describe(identity.scope());List<Object> result=new ArrayList<>();
            var current=applied.get(identity.scope());
            for(var entry:plugins.entrySet()) {
                String id=entry.getKey();var p=entry.getValue();var state=current.get(id);boolean active=effective(current,id);
                result.add(Json.obj("id",id,"title",p.get("title"),"kind",p.get("kind"),"entity",p.get("entity"),"enabled",state.enabled(),"effective",active,"revision",state.revision(),
                    "blockedReason",state.enabled()&&!active?"BUSINESS_DISABLED":"","dependency","business".equals(p.get("kind"))?"":businesses.get(str(p.get("entity")))));
            }
            return Json.obj("protocol","fcp-ingestion/1","instance",raw.get("instance"),"generation",raw.get("generation"),"caller",Json.obj("scope",identity.scope(),"subject",identity.subject()),
                "plugins",result,"entities",new ArrayList<>(businesses.keySet()),"classes",new ArrayList<>(new TreeSet<>(identity.classes())),
                "allClasses",identity.permissions().contains("fcp:ingest:allClasses"),"canImport",identity.permissions().contains("fcp:ingest:import"),"canManage",identity.permissions().contains("fcp:ingest:manage"));
        }finally{lifecycle.readLock().unlock();}
    }
    public Map<String,Object> toggle(Identity identity,String body){
        require(identity,"manage");ensure(identity);Map<String,Object> request=HostRequest.parse(body,false),input=obj(request,"input");
        if(!input.keySet().equals(Set.of("plugin","enabled","revision"))||!(input.get("enabled") instanceof Boolean)||!(input.get("revision") instanceof Number))
            throw new FcpException("INVALID_INPUT","Use plugin, boolean enabled and revision");
        String id=str(input.get("plugin"));plugin(id);
        lifecycle.writeLock().lock();try{
            current(identity,request);fence(identity);
            states.compareAndSet(key(identity.scope(),id),number(input.get("revision")),(Boolean)input.get("enabled"),identity.subject());
            ensure(identity);
            return describe(identity);
        }finally{lifecycle.writeLock().unlock();}
    }
    private Map<String,Object> stored(String scope,String entity,String classId){
        try{return records.get(scope,"ingest-"+entity,classId);}
        catch(FcpException error){if(!error.code.equals("NOT_FOUND"))throw error;return Json.obj("version",0,"body",Json.obj("classId",classId,"records",List.of()));}
    }
    public Map<String,Object> read(Identity identity,String entity,String classId){
        require(identity,"view");classAccess(identity,classId);String id=business(entity);ensure(identity);
        lifecycle.readLock().lock();try{
            live();available(identity,id);Map<String,Object> stored=stored(identity.scope(),entity,classId);
            Map<String,Object> output=invoke(identity,id,Json.obj("classId",classId,"records",arr(obj(stored,"body"),"records")));
            fence(identity);output.put("version",stored.get("version"));output.put("entity",entity);output.put("classId",classId);return output;
        }finally{lifecycle.readLock().unlock();}
    }
    private static String rowKey(Map<String,Object> row,List<Object> fields){List<String> values=new ArrayList<>();for(Object field:fields)values.add(str(row.get(str(field))));return Json.write(values);}
    private void validateRows(List<Object> rows,String classId){
        if(rows.size()>1000)throw new FcpException("RESULT_LIMIT","A class dataset is limited to 1000 records");
        for(Object value:rows)if(!classId.equals(map(value).get("classId")))throw new FcpException("FORBIDDEN","Plugin returned data outside the selected class");
    }
    private void references(Identity identity,Map<String,Object> definition,List<Object> rows,String classId){
        Map<String,Object> ref=obj(definition,"reference");if(ref.isEmpty())return;
        String target=str(ref.get("entity"));business(target);List<Object> fields=arr(ref,"fields");
        Set<String> allowed=new HashSet<>();for(Object row:arr(obj(stored(identity.scope(),target,classId),"body"),"records"))allowed.add(rowKey(map(row),fields));
        for(Object row:rows)if(!allowed.contains(rowKey(map(row),fields)))throw new FcpException("BUSINESS_RULE","Attendance references a student absent from this tenant/class roster");
    }
    public Map<String,Object> ingest(Identity identity,String body){
        require(identity,"import");ensure(identity);Map<String,Object> request=HostRequest.parse(body,false),input=obj(request,"input");
        if(!input.keySet().equals(Set.of("plugin","classId","payload","expectedVersion","requestKey"))||!(input.get("expectedVersion") instanceof Number))
            throw new FcpException("INVALID_INPUT","Import requires plugin, classId, payload, expectedVersion and requestKey");
        String id=str(input.get("plugin")),classId=str(input.get("classId")),requestKey=str(input.get("requestKey"));
        Map<String,Object> source=plugin(id);String kind=str(source.get("kind")),entity=str(source.get("entity")),businessId=business(entity);
        if(kind.equals("business"))throw new FcpException("INVALID_INPUT","Import through a connector or adapter");
        if(!requestKey.matches("[A-Za-z0-9][A-Za-z0-9_.-]{7,63}"))throw new FcpException("INVALID_INPUT","Use a unique requestKey of 8..64 safe characters");
        long expected=number(input.get("expectedVersion"));if(expected<0)throw new FcpException("INVALID_INPUT","Negative expectedVersion");classAccess(identity,classId);
        Map<String,Object> payload=obj(input,"payload");
        if(kind.equals("connector")&&!payload.isEmpty())throw new FcpException("INVALID_INPUT","Connection credentials and endpoints are host-owned");
        if(kind.equals("adapter")&&!payload.keySet().equals(Set.of("content","encoding")))throw new FcpException("INVALID_INPUT","Adapter payload requires content and encoding");
        String fingerprint=digest(canonical(input));
        lifecycle.readLock().lock();try{
            current(identity,request);available(identity,id);fence(identity);
            Map<String,Object> replay=records.replay(identity.scope(),identity.subject(),"ingest:"+requestKey,fingerprint);if(replay!=null)return receipt(replay,true);
            Map<String,Object> previous=stored(identity.scope(),entity,classId);
            if(number(previous.get("version"))!=expected)throw new FcpException("RECORD_CONFLICT","Dataset changed; reload its version");
            Map<String,Object> parseInput=new LinkedHashMap<>(payload);parseInput.put("classId",classId);
            if(kind.equals("connector")){
                Map<String,Object> config=connections.apply(identity.scope(),id);
                if(config==null||config.isEmpty())throw new FcpException("INVALID_INPUT","This tenant's connector has not been configured");
                parseInput.put("connection",cloneJson(config));
            }
            Map<String,Object> parsed=invoke(identity,id,parseInput);List<Object> batch=arr(parsed,"records");validateRows(batch,classId);
            Map<String,Object> normalized=invoke(identity,businessId,Json.obj("classId",classId,"records",batch));batch=arr(normalized,"records");validateRows(batch,classId);
            Map<String,Object> definition=plugin(businessId);List<Object> keys=arr(definition,"keyFields");
            TreeMap<String,Object> merged=new TreeMap<>();for(Object row:arr(obj(previous,"body"),"records"))merged.put(rowKey(map(row),keys),row);
            Set<String> seen=new HashSet<>();int inserted=0,updated=0,unchanged=0;
            for(Object row:batch){String rowId=rowKey(map(row),keys);if(!seen.add(rowId))throw new FcpException("INVALID_OUTPUT","Business plugin returned duplicate keys");
                Object old=merged.put(rowId,row);if(old==null)inserted++;else if(old.equals(row))unchanged++;else updated++;}
            List<Object> rows=new ArrayList<>(merged.values());validateRows(rows,classId);references(identity,definition,rows,classId);
            Map<String,Object> complete=invoke(identity,businessId,Json.obj("classId",classId,"records",rows));
            if(!canonical(rows).equals(canonical(complete.get("records"))))throw new FcpException("INVALID_OUTPUT","Business normalization must be idempotent and preserve canonical ordering");
            Map<String,Object> data=Json.obj("classId",classId,"entity",entity,"records",rows,"summary",complete.get("summary"),"sourcePlugin",id,"imported",batch.size(),"inserted",inserted,"updated",updated,"unchanged",unchanged);
            // The lease prevents same-node stop from racing this final authorization + transaction.
            fence(identity);
            Map<String,Object> saved=records.commit(identity.scope(),"ingest-"+entity,identity.subject(),"ingest:"+requestKey,fingerprint,classId,expected,data,
                Json.obj("operation","ingestion.import","plugin",id,"inputDigest",fingerprint,"imported",batch.size(),"inserted",inserted,"updated",updated,"unchanged",unchanged));
            return receipt(saved,false);
        }finally{lifecycle.readLock().unlock();}
    }
    private static Map<String,Object> receipt(Map<String,Object> saved,boolean replay){
        Map<String,Object> body=obj(saved,"body");return Json.obj("version",saved.get("version"),"entity",body.get("entity"),"classId",body.get("classId"),"imported",body.get("imported"),"inserted",body.get("inserted"),"updated",body.get("updated"),"unchanged",body.get("unchanged"),"summary",body.get("summary"),"replayed",replay);
    }
    private static Object canonical(Object value){
        if(value instanceof Map<?,?>){Map<String,Object> result=new TreeMap<>();map(value).forEach((k,v)->result.put(k,canonical(v)));return result;}
        if(value instanceof List<?> values)return values.stream().map(IngestionService::canonical).toList();return value;
    }
    private static String digest(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(value).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    @Override public void close(){lifecycle.writeLock().lock();try{if(!closed){closed=true;engine.close();applied.clear();}}finally{lifecycle.writeLock().unlock();}}
}
