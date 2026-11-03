package dev.fcp.school;

import fcp.*;
import dev.fcp.dual.*;
import java.util.*;
import java.nio.file.Path;
import java.io.IOException;
import fcp.state.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import static fcp.Schema.*;

/** Transport-neutral application adapter. The HTTP caller never supplies an Identity. */
public final class SchoolFacade implements AutoCloseable {
    public record Identity(String scope,String subject,Set<String> permissions,Set<String> classes) {
        public Identity { permissions=Set.copyOf(permissions);classes=Set.copyOf(classes); }
        public boolean can(String action){return permissions.contains("fcp:eval:"+action);}
        public boolean adminShell(){return permissions.contains("fcp:ui:admin");}
        public Map<String,Object> attributes(){return Json.obj("subject",subject,"permissions",new ArrayList<>(permissions),
            "classes",new ArrayList<>(classes),"allClasses",can("allClasses"));}
    }
    private final Engine engine;
    private final PluginStateStore states;
    private final Map<String,PluginStateStore.State> applied = new HashMap<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private final Consumer<Identity> reauthorize;
    private final boolean initialEnabled;
    private final int maxScopes;
    private boolean closed;
    private static PluginStateStore.Key stateKey(String scope) { return new PluginStateStore.Key(scope,"evaluation.stats"); }
    /** Compatibility constructor for component tests. Native hosts must provide a durable store. */
    public SchoolFacade(Path runtime,RecordStore store,Engine.Guard guard)throws IOException {
        this(runtime,store,guard,new MemoryPluginStateStore(),true,128,identity -> {});
    }
    public SchoolFacade(Path runtime,RecordStore store,Engine.Guard guard,PluginStateStore states,
                        boolean initialEnabled,int maxScopes,Consumer<Identity> reauthorize)throws IOException {
        if (maxScopes<1 || maxScopes>10000) throw new IllegalArgumentException("maxScopes");
        this.states=Objects.requireNonNull(states);this.initialEnabled=initialEnabled;
        this.maxScopes=maxScopes;this.reauthorize=Objects.requireNonNull(reauthorize);
        engine=SchoolSystem.create(runtime,store,(context,target,operation)->{
            PluginStateStore.State current=this.states.getOrCreate(stateKey(context.scope()),this.initialEnabled);
            Object revision=context.attributes.get("pluginStateRevision");
            if (!(revision instanceof Number) || ((Number)revision).longValue()!=current.revision())
                throw new FcpException("STALE_COMPOSITION","Persisted plugin state changed; reload the capability catalog");
            if(guard!=null)guard.check(context,target,operation);
        });
    }
    private void authenticated(Identity identity){
        if(identity==null||identity.subject()==null||identity.subject().isBlank())throw new FcpException("UNAUTHENTICATED","Login is required");
        if(!identity.can("list"))throw new FcpException("FORBIDDEN","Application access is not granted");
    }
    private void live() { if(closed)throw new FcpException("CLOSED","Host is closed"); }
    private void ensure(Identity identity){
        authenticated(identity);
        // Fast path holds only a read lease: unrelated admitted calls remain concurrent.
        lifecycle.readLock().lock();
        try {
            live();
            PluginStateStore.State cached=applied.get(identity.scope());
            if(cached!=null && cached.equals(states.getOrCreate(stateKey(identity.scope()),initialEnabled)))return;
        } finally {lifecycle.readLock().unlock();}
        lifecycle.writeLock().lock();
        try {
            live();
            PluginStateStore.State old=applied.get(identity.scope());
            if(old==null && applied.size()>=maxScopes)throw new FcpException("RESOURCE_LIMIT","Active tenant capacity reached");
            PluginStateStore.State desired=states.getOrCreate(stateKey(identity.scope()),initialEnabled);
            if(!desired.equals(old)){
                long generation=old==null?0:number(engine.describe(identity.scope()).get("generation"));
                engine.publish(identity.scope(),desired.enabled()?"school.full":"school.java-only",generation);
                applied.put(identity.scope(),desired);
            }
        } finally {lifecycle.writeLock().unlock();}
    }
    public Map<String,Object> describe(Identity identity){
        ensure(identity);lifecycle.readLock().lock();try {live();Map<String,Object> raw=engine.describe(identity.scope());
        boolean python=arr(raw,"modules").contains("evaluation.stats");
        List<String> operations=new ArrayList<>();
        for(String op:List.of("list","get","history","create","analyze","manage"))if(identity.can(op)&&(!op.equals("analyze")||python))operations.add(op);
        return Json.obj("protocol",raw.get("protocol"),"instance",raw.get("instance"),"generation",raw.get("generation"),
                "caller",Json.obj("scope",identity.scope(),"subject",identity.subject()),
                "shell",identity.adminShell()?"admin":"teacher","allowedShells",identity.adminShell()?List.of("admin","teacher"):List.of("teacher"),
                "operations",operations,"classes",identity.can("allClasses")?List.of("class-a","class-b"):new ArrayList<>(identity.classes()),
                "pythonEnabled",python,"stateRevision",applied.get(identity.scope()).revision(),"composition",raw.get("composition"));
        } finally {lifecycle.readLock().unlock();}
    }
    public Object invoke(Identity identity,String operation,String body){
        ensure(identity);Map<String,Object> request=HostRequest.parse(body,false);
        HostRequest.checkCaller(request,identity.scope(),identity.subject());
        lifecycle.readLock().lock();
        try {
            live();Map<String,Object> attributes=identity.attributes();
            attributes.put("pluginStateRevision",applied.get(identity.scope()).revision());
            return engine.invoke(identity.scope(),"evaluation",operation,obj(request,"input"),attributes,
                                 number(request.get("generation")),str(request.get("instance")));
        } finally {lifecycle.readLock().unlock();}
    }
    public Map<String,Object> compose(Identity identity,String body){
        ensure(identity);if(!identity.can("manage"))throw new FcpException("FORBIDDEN","Plugin administration is not granted");
        Map<String,Object> request=HostRequest.parse(body,false);HostRequest.checkCaller(request,identity.scope(),identity.subject());
        Map<String,Object> input=obj(request,"input");
        if(!input.keySet().equals(Set.of("enabled"))||!(input.get("enabled") instanceof Boolean))throw new FcpException("INVALID_INPUT","Only a boolean enabled is accepted");
        lifecycle.writeLock().lock();
        try {
            live();reauthorize.accept(identity);
            Map<String,Object> current=engine.describe(identity.scope());
            long generation=number(current.get("generation"));
            if(!current.get("instance").equals(request.get("instance")) || generation!=number(request.get("generation")))
                throw new FcpException("STALE_COMPOSITION","Host instance or composition changed");
            PluginStateStore.State old=applied.get(identity.scope());
            // Persist desired state before publishing. A failed publish remains recoverable and fail-closed.
            PluginStateStore.State saved=states.compareAndSet(stateKey(identity.scope()),old.revision(),(Boolean)input.get("enabled"),identity.subject());
            engine.publish(identity.scope(),saved.enabled()?"school.full":"school.java-only",generation);
            applied.put(identity.scope(),saved);
        } finally {lifecycle.writeLock().unlock();}
        return describe(identity);
    }
    public Map<String,Object> metrics(Identity identity){authenticated(identity);if(!identity.can("manage"))throw new FcpException("FORBIDDEN","Runtime metrics are restricted");return ProcessProvider.metrics();}
    @Override public void close(){
        lifecycle.writeLock().lock();try{if(!closed){closed=true;engine.close();applied.clear();}}
        finally{lifecycle.writeLock().unlock();}
    }
}
