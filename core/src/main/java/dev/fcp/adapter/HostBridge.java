package dev.fcp.adapter;
import fcp.Engine;
import fcp.FcpException;
import java.util.*;
import java.util.function.Supplier;

/** Framework-neutral embedding. Identity is always provided by the authenticated host. */
public final class HostBridge implements AutoCloseable {
    public record Caller(String scope,String subject,Map<String,Object> attributes){
        public Caller{Objects.requireNonNull(scope);Objects.requireNonNull(subject);attributes=Map.copyOf(attributes);if(attributes.containsKey("scope")||attributes.containsKey("subject"))throw new IllegalArgumentException("Identity fields are host-owned");}
        Map<String,Object> context(){Map<String,Object> context=new LinkedHashMap<>(attributes);context.put("subject",subject);return context;}
    }
    private final Engine engine;
    private final Supplier<Caller> authenticatedCaller;
    private final String initialComposition;
    private final Set<String> installed=new HashSet<>();
    public HostBridge(Engine engine,Supplier<Caller> authenticatedCaller,String initialComposition){this.engine=Objects.requireNonNull(engine);this.authenticatedCaller=Objects.requireNonNull(authenticatedCaller);this.initialComposition=Objects.requireNonNull(initialComposition);}
    private synchronized Caller caller(){Caller caller=Objects.requireNonNull(authenticatedCaller.get(),"Authenticated caller required");if(!installed.contains(caller.scope())){engine.publish(caller.scope(),initialComposition,0);installed.add(caller.scope());}return caller;}
    public Map<String,Object> describe(){return engine.describe(caller().scope());}
    public Object invoke(String capability,String operation,Map<String,Object> input,long expected,String instance){
        if(instance==null||instance.isBlank())throw new FcpException("INVALID_INPUT","Composition instance is required");Caller caller=caller();return engine.invoke(caller.scope(),capability,operation,input,caller.context(),expected,instance);
    }
    /** Only call after an independent host-admin authorization check. */
    public Map<String,Object> publishByAdministrator(Object composition,long expected,boolean authorized){if(!authorized)throw new FcpException("FORBIDDEN","Composition administration denied");return engine.publish(caller().scope(),composition,expected);}
    public void close(){engine.close();}
}
