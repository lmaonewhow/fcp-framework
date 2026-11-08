package dev.fcp.host;

import fcp.*;
import fcp.control.StateJournal;
import fcp.host.*;
import fcp.observability.TraceContext;
import fcp.runtime.multiversion.PluginReleaseRuntime;
import fcp.release.version.VersionCodec;
import java.io.IOException;
import java.util.*;
import static fcp.Schema.*;

/** Operator-triggered, deployment-registered TASK identity. No browser-supplied principal. */
public final class ReleaseTaskApplication {
    public record Definition(String id,String tenant,String principal,String plugin,String capability) {
        public Definition {
            for(String value:List.of(id,principal,plugin,capability))if(!value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))throw new IllegalArgumentException("Invalid task definition");
            if(!tenant.matches("[A-Za-z0-9_-]{1,20}")||principal.equals("fcp-control-recovery"))throw new IllegalArgumentException("Invalid task identity");
        }
        Map<String,Object> json(){return Map.of("id",id,"tenant",tenant,"principal",principal,"plugin",plugin,"capability",capability);}
    }
    private final HostCapabilities host;private final PluginReleaseRuntime runtime;private final StateJournal journal;private final Map<String,Definition> definitions;
    public ReleaseTaskApplication(HostCapabilities host,PluginReleaseRuntime runtime,StateJournal journal,List<Definition> definitions){
        this.host=Objects.requireNonNull(host);this.runtime=Objects.requireNonNull(runtime);this.journal=Objects.requireNonNull(journal);
        Map<String,Definition> copy=new TreeMap<>();for(var d:definitions)if(copy.put(d.id(),d)!=null)throw new IllegalArgumentException("Duplicate task definition");
        if(copy.isEmpty()||copy.size()>64)throw new IllegalArgumentException("Task definition limit");this.definitions=Map.copyOf(copy);
    }
    public static List<Definition> parse(String json){
        var root=map(Json.parse(json));if(!root.keySet().equals(Set.of("format","tasks"))||number(root.get("format"))!=1)throw new IllegalArgumentException("Task definition format");
        List<Definition> result=new ArrayList<>();for(Object item:list(root.get("tasks"))){var b=map(item);if(!b.keySet().equals(Set.of("id","tenant","principal","plugin","capability")))throw new IllegalArgumentException("Task definition fields");result.add(new Definition(str(b.get("id")),str(b.get("tenant")),str(b.get("principal")),str(b.get("plugin")),str(b.get("capability"))));}return result;
    }
    private void admin(TrustedContext c){if(c.principal().kind()!=TrustedContext.Kind.USER||!c.tenantId().equals("000000"))throw new FcpException("PERMISSION_DENIED","Platform USER operator required");host.require(c,new HostCapabilities.Grant("control","system.release","manage","tenant:"+c.tenantId()));}
    private Definition definition(String id){var d=definitions.get(id);if(d==null)throw new FcpException("TASK_DEFINITION_MISSING","Task not registered by deployment");return d;}
    private TrustedContext context(Definition d){var trace=Objects.requireNonNull(TraceContext.current(),"TASK entry trace");return new TrustedContext(trace.requestId(),trace.traceId(),d.tenant(),new TrustedContext.Principal(TrustedContext.Kind.TASK,d.principal()),0,Set.of("tenant:"+d.tenant()),host.clock().instant().plusSeconds(60));}
    private static String hash(Object value){return VersionCodec.sha256(VersionCodec.canonical(value));}
    private static String key(String id,String run){if(run==null||!run.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))throw new IllegalArgumentException("Invalid run ID");return hash(List.of(id,run));}
    private Map<String,Object> load(StateJournal.Snapshot s){return s.json()==null?new LinkedHashMap<>(Map.of("format",1,"runs",new LinkedHashMap<>(),"audit",new ArrayList<>())):map(Json.parse(s.json()));}
    private void save(StateJournal.Snapshot s,Map<String,Object> data)throws IOException{journal.compareAndSet(s.revision(),Json.write(data));}
    private void audit(Map<String,Object> data,TrustedContext operator,Definition d,String run,String action){list(data.get("audit")).add(Map.of("operator",operator.principal().id(),"tenant",d.tenant(),"taskPrincipal",d.principal(),"definition",d.id(),"run",run,"action",action,"at",host.clock().instant().toString(),"traceId",TraceContext.current().traceId(),"requestId",TraceContext.current().requestId()));}
    /** Explicit control metadata allowlist. Never project input, result, or their nested copies. */
    private Map<String,Object> view(Map<String,Object> record){
        Map<String,Object> out=new LinkedHashMap<>();
        for(String field:List.of("definition","runId","taskId","state"))if(record.containsKey(field))out.put(field,Json.copy(record.get(field)));
        if(record.containsKey("pinned")){
            Map<String,Object> pinned=map(record.get("pinned")),safe=new LinkedHashMap<>();
            for(String field:List.of("release","plugin","capability","version","digest","state","taskId"))if(pinned.containsKey(field))safe.put(field,Json.copy(pinned.get(field)));
            out.put("pinned",safe);
        }
        return out;
    }
    private Map<String,Object> authorizedResult(Map<String,Object> record){var out=view(record);out.put("result",Json.copy(record.get("result")));return out;}
    private Map<String,Object> record(Map<String,Object> data,Definition d,String key){Object raw=map(data.get("runs")).get(key);if(raw==null)throw new FcpException("TASK_RUN_MISSING","Pin task before execution");var r=map(raw);if(!hash(d.json()).equals(r.get("definitionHash")))throw new FcpException("TASK_DEFINITION_CHANGED","Deployed task definition changed; reconcile existing run");return r;}
    public synchronized Map<String,Object> status(TrustedContext operator,String id,String run)throws IOException{admin(operator);var d=definition(id);var r=record(load(journal.load()),d,key(id,run));return view(r);}
    public synchronized Map<String,Object> pin(TrustedContext operator,String id,String run,Map<String,Object> input)throws IOException{
        // Correlation only: keep deployment TASK authority separate from the calling USER.
        try(var scope=TraceContext.open(TraceContext.ensure())){return pinScoped(operator,id,run,input);}
    }
    private Map<String,Object> pinScoped(TrustedContext operator,String id,String run,Map<String,Object> input)throws IOException{
        admin(operator);Definition d=definition(id);String k=key(id,run);if(Json.write(input).length()>65536)throw new IllegalArgumentException("Task input limit");
        var snapshot=journal.load();var data=load(snapshot);var runs=map(data.get("runs"));
        if(runs.containsKey(k)){var old=record(data,d,k);if(!hash(input).equals(old.get("inputHash")))throw new FcpException("IDEMPOTENCY_CONFLICT","Task input changed");return view(old);}
        if(runs.size()>=10000)throw new FcpException("TASK_RUN_LIMIT","Task retention capacity reached; no automatic deletion");
        Map<String,Object> r=new LinkedHashMap<>(Map.of("definition",id,"definitionHash",hash(d.json()),"runId",run,"taskId",k,"input",Json.copy(input),"inputHash",hash(input),"state","PINNING"));
        runs.put(k,r);audit(data,operator,d,run,"PIN_REQUESTED");save(snapshot,data);
        // Unknown pin completion is deliberately not retried on restart.
        var pinned=runtime.pinTask(context(d),null,k,d.plugin(),d.capability(),map(r.get("input")));
        snapshot=journal.load();data=load(snapshot);r=record(data,d,k);r.put("pinned",pinned);r.put("state","PINNED");audit(data,operator,d,run,"PINNED");save(snapshot,data);return view(r);
    }
    public synchronized Map<String,Object> run(TrustedContext operator,String id,String run)throws IOException{
        // Correlation only: keep deployment TASK authority separate from the calling USER.
        try(var scope=TraceContext.open(TraceContext.ensure())){return runScoped(operator,id,run);}
    }
    private Map<String,Object> runScoped(TrustedContext operator,String id,String run)throws IOException{
        admin(operator);Definition d=definition(id);String k=key(id,run);var snapshot=journal.load();var data=load(snapshot);var r=record(data,d,k);String state=str(r.get("state"));
        if(!Set.of("PINNED","COMPLETE").contains(state))throw new FcpException("TASK_RUN_UNCERTAIN","Reconcile task outcome; automatic replay is forbidden");
        var input=map(r.get("input"));var task=context(d);
        runtime.pinTask(task,null,k,d.plugin(),d.capability(),input); // Live exact pinned-package authorization, even for completed reads.
        if(state.equals("COMPLETE"))return authorizedResult(r);
        r.put("state","EXECUTING");audit(data,operator,d,run,"RUN_REQUESTED");save(snapshot,data);
        Object result;
        try{result=runtime.invokeTask(task,k,input);}catch(IOException|RuntimeException failure){
            try{snapshot=journal.load();data=load(snapshot);r=record(data,d,k);r.put("state","UNKNOWN");audit(data,operator,d,run,"UNKNOWN");save(snapshot,data);}catch(Exception persistence){failure.addSuppressed(persistence);}throw failure;
        }
        snapshot=journal.load();data=load(snapshot);r=record(data,d,k);r.put("state","COMPLETE");r.put("result",Json.copy(result));audit(data,operator,d,run,"COMPLETE");save(snapshot,data);
        runtime.pinTask(context(d),null,k,d.plugin(),d.capability(),input); // Recheck before returning the business result.
        return authorizedResult(r);
    }
}
