package fcp.storage;

import fcp.FcpException;
import fcp.host.TrustedContext;
import java.util.*;

/** JSON boundary for authenticated host callbacks. Server routing supplies plugin and context.
 * Input may select an authorized data scope; every access rechecks it through HostCapabilities.
 * Never register this endpoint as lifecycle, grant management, schema deployment or raw SQL.
 */
public final class StorageCapabilities {
    private final JdbcPluginStorage storage;
    public StorageCapabilities(JdbcPluginStorage storage){this.storage=Objects.requireNonNull(storage);}
    public static List<fcp.plugin.PluginManifest.Capability> contracts(){
        List<fcp.plugin.PluginManifest.Capability> out=new ArrayList<>();
        for(String op:List.of("get","list","put","delete","restore")){
            Map<String,Object> props=new LinkedHashMap<>();
            props.put("collection",Map.of("type","string","minLength",1,"maxLength",64));
            props.put("dataScope",Map.of("type","string","minLength",1,"maxLength",192));
            List<String> required=new ArrayList<>(List.of("collection","dataScope"));
            if(op.equals("list")){
                props.put("equals",Map.of("type","object"));props.put("after",Map.of("type","string","maxLength",64));props.put("limit",Map.of("type","integer","minimum",1,"maximum",100));
            }else{props.put("id",Map.of("type","string","minLength",1,"maxLength",64));required.add("id");}
            boolean write=!op.equals("get")&&!op.equals("list");
            if(write){props.put("expectedRevision",Map.of("type","integer","minimum",0));props.put("idempotencyKey",Map.of("type","string","minLength",1,"maxLength",128));required.addAll(List.of("expectedRevision","idempotencyKey"));}
            if(op.equals("put")){props.put("body",Map.of("type","object"));required.add("body");}
            out.add(new fcp.plugin.PluginManifest.Capability("host.storage."+op,"1.0.0","storage."+(op.equals("get")||op.equals("list")?"read":op.equals("put")?"write":op),write,true,30000,Map.of("type","object","properties",props,"required",required,"additionalProperties",false),Map.of()));
        }
        return List.copyOf(out);
    }
    public Object invoke(String serverPluginId,TrustedContext context,String operation,Map<String,Object> input){
        Set<String> allowed=switch(operation){
            case "get" -> Set.of("collection","id","dataScope");
            case "list" -> Set.of("collection","equals","after","limit","dataScope");
            case "put" -> Set.of("collection","id","body","expectedRevision","idempotencyKey","dataScope");
            case "delete","restore" -> Set.of("collection","id","expectedRevision","idempotencyKey","dataScope");
            default -> throw new FcpException("INVALID_ARGUMENT","Unknown storage operation");
        };
        if(!allowed.containsAll(input.keySet()))throw new FcpException("INVALID_ARGUMENT","Unknown storage input field");
        String collection=string(input,"collection"),scope=string(input,"dataScope");
        var session=storage.bind(serverPluginId,context,scope);
        return switch(operation){
            case "get" -> session.get(collection,string(input,"id")).map(StorageCapabilities::json).orElse(null);
            case "list" -> session.list(collection,map(input.getOrDefault("equals",Map.of())),input.get("after")==null?null:string(input,"after"),Math.toIntExact(integer(input.getOrDefault("limit",100)))).stream().map(StorageCapabilities::json).toList();
            case "put" -> json(session.put(collection,string(input,"id"),map(input.get("body")),integer(input.get("expectedRevision")),string(input,"idempotencyKey")));
            case "delete" -> json(session.delete(collection,string(input,"id"),integer(input.get("expectedRevision")),string(input,"idempotencyKey")));
            case "restore" -> json(session.restore(collection,string(input,"id"),integer(input.get("expectedRevision")),string(input,"idempotencyKey")));
            default -> throw new AssertionError();
        };
    }
    public static Map<String,Object> json(JdbcPluginStorage.Record r){return Map.of("id",r.id(),"body",r.body(),"revision",r.revision(),"deleted",r.deleted());}
    private static String string(Map<String,Object> m,String k){if(!(m.get(k) instanceof String s)||s.isBlank())throw new IllegalArgumentException("Missing string field");return s;}
    private static long integer(Object v){if(!(v instanceof Number n))throw new IllegalArgumentException("Missing integer");try{return new java.math.BigDecimal(n.toString()).longValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException("Invalid integer");}}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object o){if(!(o instanceof Map<?,?> m)||m.keySet().stream().anyMatch(k->!(k instanceof String)))throw new IllegalArgumentException("Expected object");return (Map<String,Object>)m;}
}
