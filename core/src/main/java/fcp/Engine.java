package fcp;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import static fcp.Schema.*;

/** FCP/0.3-compatible kernel with pinned invocations and deferred resource disposal.
 * Provider code never runs while holding the invocation admission monitor.
 * atomic() keeps commits serialized with publication without holding the admission monitor across I/O.
 */
public final class Engine implements AutoCloseable {
    public interface Provider extends AutoCloseable {
        Object invoke(Context context,String export,String operation,Map<String,Object> input);
        default void close(){}
    }
    @FunctionalInterface public interface Factory {Provider create(Map<String,Object> manifest,String scope);}
    @FunctionalInterface public interface Guard {void check(Context context,String target,String operation);}
    private record Snapshot(String scope,long generation,Map<String,Object> plan,Map<String,Map<String,Object>> definitions,Map<String,Map<String,Object>> contracts,Map<String,Provider> instances,List<String> order){}
    private static final class Invocation {
        final Thread owner = Thread.currentThread();
        volatile boolean live = true;
        boolean committed;
        void check() {
            if (!live) throw new FcpException("CONTEXT_CLOSED", "Invocation has completed");
            if (Thread.currentThread() != owner) throw new FcpException("CONTEXT_THREAD", "Explicit delegation is required for another thread");
        }
    }
    private final Object publication = new Object();
    private final Object commitFence = new Object();
    private final Map<Snapshot,Integer> active = new IdentityHashMap<>();
    private final Set<Snapshot> retired = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Provider,Integer> references = new IdentityHashMap<>();
    private final List<Provider> disposalQueue = new ArrayList<>();
    private static Object immutable(Object value) {
        if(value instanceof Map<?,?> values) {
            Map<String,Object> result=new LinkedHashMap<>();
            values.forEach((key,item)->result.put((String)key,immutable(item)));
            return Collections.unmodifiableMap(result);
        }
        if(value instanceof List<?> values) {
            List<Object> result=new ArrayList<>();
            values.forEach(item->result.add(immutable(item)));
            return Collections.unmodifiableList(result);
        }
        return value;
    }
    public final class Context {
        private final Snapshot snapshot;
        public final Map<String,Object> attributes;
        public final String module;
        private final int depth;
        private final String target,operation;
        private final Invocation invocation;
        private Context(Snapshot s,Map<String,Object> a,String m,int d,String t,String o,Invocation i){snapshot=s;attributes=a;module=m;depth=d;target=t;operation=o;invocation=i;}
        public String scope(){return snapshot.scope();}
        public long generation(){return snapshot.generation();}
        public Map<String,Object> profile(){return map(cloneJson(obj(snapshot.plan(),"profile")));}
        public Object call(String dependency,String op,Map<String,Object> input){invocation.check();Object ref=obj(snapshot.plan(),"bindings").get(module+"/"+dependency);if(ref==null)throw new FcpException("DEPENDENCY_MISSING",dependency);return dispatch(this,str(ref),op,input);}
        public Object atomic(Supplier<Object> action){
            invocation.check();
            synchronized(commitFence){
                invocation.check();current(snapshot);authorize(this,target,operation);
                Object result=action.get();invocation.committed=true;return result;
            }
        }
        public Object policy(String ref,Map<String,Object> input){invocation.check();String[] target=ref.split("/");Provider provider=snapshot.instances().get(target[0]);if(provider==null)throw new FcpException("POLICY_MISSING",ref);return provider.invoke(this,target[1],"authorize",input);}
        public String effect(String ref,String op){String[] p=ref.split("/");String c=str(obj(snapshot.definitions().get(p[0]),"provides").get(p[1]));return (String)map(obj(snapshot.contracts().get(c),"operations").get(op)).getOrDefault("effect","read");}
    }
    public final Registry registry;
    private final Guard guard;
    private final Consumer<Map<String,Object>> uiValidator;
    private final String instance=UUID.randomUUID().toString();
    private final Map<String,Snapshot> snapshots=new HashMap<>();
    private boolean closed;
    public final List<String> cleanupErrors=Collections.synchronizedList(new ArrayList<>());
    public Engine(Registry registry,Guard guard){this(registry,guard,null);}
    public Engine(Registry registry,Guard guard,Consumer<Map<String,Object>> uiValidator){this.registry=registry;this.guard=guard;this.uiValidator=uiValidator;}
    private synchronized void current(Snapshot snapshot){if(closed||snapshots.get(snapshot.scope())!=snapshot)throw new FcpException("STALE_COMPOSITION","Composition changed");}
    private void authorize(Context context,String target,String operation){if(guard!=null)guard.check(context,target,operation);}
    private String contract(Map<String,Map<String,Object>> modules,String target){String[] p=target.split("/",-1);if(p.length!=2||!modules.containsKey(p[0])||!obj(modules.get(p[0]),"provides").containsKey(p[1]))throw new FcpException("TARGET_MISSING",target);return str(obj(modules.get(p[0]),"provides").get(p[1]));}
    private void visit(String m,Map<String,List<String>> edges,Set<String> active,Set<String> done,List<String> order){if(active.contains(m))throw new FcpException("DEPENDENCY_CYCLE",m);if(done.contains(m))return;active.add(m);for(String d:edges.get(m))visit(d,edges,active,done,order);active.remove(m);done.add(m);order.add(m);}
    public Map<String,Object> publish(String scope,Object application,long expected){
        // Only publishers serialize with one another; slow preparation does not block calls.
        synchronized(publication) { try { return prepareAndPublish(scope,application,expected); }
        finally { disposeQueued(); } }
    }
    private Map<String,Object> prepareAndPublish(String scope,Object application,long expected){
        ident(scope); final Snapshot old;
        synchronized(this) {
            if(closed)throw new FcpException("CLOSED","Engine closed");
            old=snapshots.get(scope);
            if((old==null?0:old.generation())!=expected)throw new FcpException("STALE_COMPOSITION","Publication version conflict");
        }
        Map<String,Object> plan=registry.expand(application);Map<String,Map<String,Object>> definitions=new LinkedHashMap<>();Map<String,List<String>> edges=new LinkedHashMap<>();
        for(Object name:arr(plan,"modules")){String id=str(name);Map<String,Object> definition=registry.modules.get(id);if(definition==null)throw new FcpException("MODULE_MISSING",id);definition=map(cloneJson(definition));if(!registry.factories.containsKey(obj(definition,"runtime").get("provider")))throw new FcpException("RUNTIME_MISSING",id);definitions.put(id,definition);edges.put(id,new ArrayList<>());}
        for(var entry:obj(plan,"bindings").entrySet()){
            String[] key=entry.getKey().split("/",-1);if(key.length!=2||!definitions.containsKey(key[0])||!obj(definitions.get(key[0]),"requires").containsKey(key[1]))throw new FcpException("BINDING",entry.getKey());
            String target=str(entry.getValue());String actual=contract(definitions,target);String required=str(map(obj(definitions.get(key[0]),"requires").get(key[1])).get("contract"));
            if(!actual.equals(required))throw new FcpException("CONTRACT_MISMATCH",entry.getKey());edges.get(key[0]).add(target.split("/")[0]);
        }
        for(var entry:definitions.entrySet())for(var req:obj(entry.getValue(),"requires").entrySet())if(!Boolean.TRUE.equals(map(req.getValue()).get("optional"))&&!obj(plan,"bindings").containsKey(entry.getKey()+"/"+req.getKey()))throw new FcpException("DEPENDENCY_MISSING",entry.getKey()+"/"+req.getKey());
        for(var export:obj(plan,"exports").entrySet()){ident(export.getKey());contract(definitions,str(export.getValue()));}
        List<String> order=new ArrayList<>();Set<String> active=new HashSet<>(),done=new HashSet<>();for(String m:definitions.keySet())visit(m,edges,active,done,order);
        plan.put("_declared_ui",cloneJson(arr(plan,"ui")));for(var definition:definitions.values())arr(plan,"ui").addAll(arr(definition,"ui"));Set<String> uiIds=new HashSet<>();
        for(Object value:arr(plan,"ui")){Map<String,Object> ui=map(value);ident(ui.get("id"));ident(ui.get("component"));if(!uiIds.add(str(ui.get("id"))))throw new FcpException("UI","Duplicate contribution");Object target=obj(plan,"exports").get(ui.get("capability"));if(target==null)throw new FcpException("UI","Unexported capability");String c=contract(definitions,str(target));if(!obj(registry.contracts.get(c),"operations").containsKey(ui.get("operation")))throw new FcpException("UI","Unknown operation");if(uiValidator!=null)uiValidator.accept(map(cloneJson(ui)));}
        Map<String,Provider> instances=new LinkedHashMap<>();List<Provider> created=new ArrayList<>();
        try{
            for(String m:order){Provider provider=old!=null&&definitions.get(m).equals(old.definitions().get(m))?old.instances().get(m):null;if(provider==null){provider=registry.factories.get(obj(definitions.get(m),"runtime").get("provider")).create(map(cloneJson(definitions.get(m))),scope);created.add(provider);}instances.put(m,provider);}
        }catch(RuntimeException error){Collections.reverse(created);for(Provider provider:created)closeOne(provider);throw error;}
        Map<String,Map<String,Object>> contracts=new LinkedHashMap<>();
        // Pin only contracts actually referenced by this composition, not the entire catalog.
        for (var definition : definitions.values()) {
            for (Object id : obj(definition,"provides").values())
                contracts.computeIfAbsent(str(id), key -> map(immutable(cloneJson(registry.contracts.get(key)))));
            for (Object requirement : obj(definition,"requires").values()) {
                String id=str(map(requirement).get("contract"));
                contracts.computeIfAbsent(id, key -> map(immutable(cloneJson(registry.contracts.get(key)))));
            }
        }
        plan=map(immutable(plan));
        synchronized(commitFence) {
        synchronized(this) {
            if(closed||snapshots.get(scope)!=old) {
                for(Provider provider:created)disposalQueue.add(provider);
                throw new FcpException("STALE_COMPOSITION","Publication changed while preparing");
            }
            Snapshot next=new Snapshot(scope,expected+1,plan,definitions,contracts,instances,List.copyOf(order));
            for(Provider provider:instances.values())references.merge(provider,1,Integer::sum);
            snapshots.put(scope,next);
            if(old!=null) { retired.add(old); releaseRetired(old); }
            return describe(scope);
        }
        }
    }
    private void closeOne(Provider provider){try{provider.close();}catch(Exception error){synchronized(cleanupErrors){if(cleanupErrors.size()>=128)cleanupErrors.remove(0);cleanupErrors.add(error.toString());}}}
    public Object invoke(String scope,String capability,String operation,Map<String,Object> input,Map<String,Object> attributes,Long expected){return invoke(scope,capability,operation,input,attributes,expected,null);}
    public Object invoke(String scope,String capability,String operation,Map<String,Object> input,Map<String,Object> attributes,Long expected,String expectedInstance){
        final Snapshot snapshot; final String target;
        synchronized(this) {
            snapshot=snapshots.get(scope);
            if(snapshot==null)throw new FcpException("SCOPE_MISSING",scope);
            current(snapshot);
            if((expected!=null&&expected!=snapshot.generation())||(expectedInstance!=null&&!expectedInstance.equals(instance)))throw new FcpException("STALE_COMPOSITION","Client snapshot changed");
            Object found=obj(snapshot.plan(),"exports").get(capability);
            if(found==null)throw new FcpException("CAPABILITY_MISSING",capability);
            target=str(found);
            active.merge(snapshot,1,Integer::sum);
        }
        Invocation invocation = new Invocation();
        try {
            return dispatch(new Context(snapshot,map(immutable(cloneJson(attributes))),"",0,"","",invocation),target,operation,map(cloneJson(input)));
        } finally {
            invocation.live = false;
            synchronized(this) {
                int count=active.getOrDefault(snapshot,0)-1;
                if(count<=0)active.remove(snapshot);else active.put(snapshot,count);
                releaseRetired(snapshot);
            }
            disposeQueued();
        }
    }
    private void releaseRetired(Snapshot snapshot) {
        // Called only under the monitor. Queues cleanup; never invokes plugin code here.
        if(!retired.contains(snapshot)||active.getOrDefault(snapshot,0)>0)return;
        retired.remove(snapshot);
        List<String> reverse=new ArrayList<>(snapshot.order());Collections.reverse(reverse);
        for(String module:reverse) {
            Provider provider=snapshot.instances().get(module);
            int count=references.getOrDefault(provider,1)-1;
            if(count==0){references.remove(provider);disposalQueue.add(provider);}
            else references.put(provider,count);
        }
    }
    private void disposeQueued() {
        List<Provider> queue;
        synchronized(this){queue=new ArrayList<>(disposalQueue);disposalQueue.clear();}
        for(Provider provider:queue)closeOne(provider);
    }
    private Object dispatch(Context parent,String target,String operation,Map<String,Object> input){
        parent.invocation.check();current(parent.snapshot);if(parent.depth>=24)throw new FcpException("CALL_DEPTH","Depth exceeds 24");String[] p=target.split("/");String c=str(obj(parent.snapshot.definitions().get(p[0]),"provides").get(p[1]));Object operationSchema=obj(parent.snapshot.contracts().get(c),"operations").get(operation);
        if(operationSchema==null)throw new FcpException("OPERATION_MISSING",operation);Map<String,Object> spec=map(operationSchema),data=map(cloneJson(input));Schema.validate(data,map(spec.get("input")));
        Context context=new Context(parent.snapshot,parent.attributes,p[0],parent.depth+1,target,operation,parent.invocation);authorize(context,target,operation);Object result=cloneJson(parent.snapshot.instances().get(p[0]).invoke(context,p[1],operation,data));
        try{Schema.validate(result,map(spec.get("output")));}catch(FcpException error){throw new FcpException("INVALID_OUTPUT",error.getMessage(),error);}if(!parent.invocation.committed)current(parent.snapshot);return result;
    }
    public synchronized Map<String,Object> describe(String scope){
        Snapshot s=snapshots.get(scope);if(s==null)throw new FcpException("SCOPE_MISSING",scope);Map<String,Object> capabilities=new LinkedHashMap<>();
        for(var e:obj(s.plan(),"exports").entrySet()){String target=str(e.getValue()),c=contract(s.definitions(),target);capabilities.put(e.getKey(),Json.obj("target",target,"contract",s.contracts().get(c)));}
        return map(cloneJson(Json.obj("protocol","fcp/0.3","instance",instance,"scope",scope,"generation",s.generation(),"composition",s.plan().get("id"),"modules",s.plan().get("modules"),"bindings",s.plan().get("bindings"),"capabilities",capabilities,"ui",s.plan().get("ui"),"profile",s.plan().get("profile"))));
    }
    public void close(){
        synchronized(publication) {
            synchronized(commitFence) {
            synchronized(this) {
                if(closed)return;
                closed=true;
                for(Snapshot snapshot:snapshots.values()){retired.add(snapshot);releaseRetired(snapshot);}
                snapshots.clear();
            }
            }
        }
        // An admitted call owns its providers until finally releases its snapshot.
        // trusted-native calls are cooperative and cannot be forcibly terminated.
        disposeQueued();
    }
}
