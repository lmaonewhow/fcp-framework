import fcp.FcpException;
import fcp.Json;
import org.dromara.fcp.plugin.FcpPluginAdvice;
import java.util.Map;
/** Run with the actual built native JAR classes and its Spring dependencies, not servlet stubs. */
public final class PluginAdviceContractProbe {
    public static void main(String[] args){
        var advice=new FcpPluginAdvice();
        var response=advice.handle(new FcpException("HOST_RESULT_IDENTITY_VIOLATION","Synthetic detail must not be exposed"));
        Map<String,Object> body=response.getBody();
        if(response.getStatusCode().value()!=503||!body.get("code").equals(503)||!body.get("msg").equals("Plugin operation unavailable; use the operation receipt to reconcile"))throw new AssertionError("Native identity-violation HTTP contract changed");
        var data=(Map<?,?>)body.get("data");
        if(!data.keySet().equals(java.util.Set.of("fcpCode","traceId"))||!data.get("fcpCode").equals("HOST_RESULT_IDENTITY_VIOLATION"))throw new AssertionError("Typed rejection lacks exact native code");
        if(!(data.get("traceId") instanceof String trace)||!trace.matches("(?:[a-f0-9]{32}|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})"))throw new AssertionError("Invalid supported trace identifier");
        var unknown=advice.handle(new FcpException("OUTCOME_UNKNOWN","must remain unknown"));
        if(unknown.getStatusCode().value()!=503||((Map<?,?>)unknown.getBody().get("data")).get("fcpCode").equals(data.get("fcpCode")))throw new AssertionError("Unknown 503 confused with typed security rejection");
        System.out.println(Json.write(Map.of("status","PASS","scope","Actual built native FcpPluginAdvice ResponseEntity; not a live HTTP request","httpStatus",response.getStatusCode().value(),"body",body)));
    }
}
