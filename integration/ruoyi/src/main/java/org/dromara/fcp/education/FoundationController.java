package org.dromara.fcp.education;

import fcp.Json;
import fcp.FcpException;
import fcp.education.FoundationCapabilities;
import fcp.education.FoundationStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Native session identity is always resolved server-side; every domain call revalidates authority. */
@RestController
@RequestMapping("/fcp/education/foundation")
public final class FoundationController {
    private final NativeFoundationIdentity identity;
    private final ObjectProvider<FoundationStore> stores;
    public FoundationController(NativeFoundationIdentity identity,ObjectProvider<FoundationStore> stores) {
        this.identity=identity;this.stores=stores;
    }
    private static ResponseEntity<Map<String,Object>> ok(Object value) {
        return ResponseEntity.ok(Map.of("code",200,"msg","","data",value));
    }
    private FoundationStore store() {
        FoundationStore value=stores.getIfAvailable();
        if(value==null)throw new Disabled();
        return value;
    }
    private static final class Disabled extends RuntimeException {}
    @GetMapping("/capabilities")
    public ResponseEntity<Map<String,Object>> capabilities(@RequestParam Map<String,String> parameters) {
        var actor=identity.current();
        if (!parameters.isEmpty()) throw new IllegalArgumentException("Unexpected query parameters");
        FoundationStore value=stores.getIfAvailable();
        if(value==null){identity.requireDiscovery();return ok(FoundationCapabilities.blocked());}
        return ok(value.capabilities(actor.tenant(),actor.actor()));
    }
    @GetMapping("/schools")
    public ResponseEntity<Map<String,Object>> schools(@RequestParam Map<String,String> parameters) {
        var actor=identity.current();
        if(!Set.of("afterId","limit").containsAll(parameters.keySet()))throw new IllegalArgumentException("Unexpected query parameters");
        String raw=parameters.getOrDefault("limit","50");
        if(!raw.matches("[1-9][0-9]{0,2}"))throw new IllegalArgumentException("Invalid page size");
        int limit=Integer.parseInt(raw);if(limit>100)throw new IllegalArgumentException("Invalid page size");
        return ok(store().schools(actor.tenant(),actor.actor(),parameters.get("afterId"),limit));
    }
    @GetMapping("/schools/{schoolId}/{kind:organizations|classes|terms}")
    public ResponseEntity<Map<String,Object>> list(@PathVariable String schoolId,@PathVariable String kind,
                                                   @RequestParam Map<String,String> parameters) {
        var actor=identity.current();
        if(!Set.of("afterId","limit","termId").containsAll(parameters.keySet()))throw new IllegalArgumentException("Unexpected query parameters");
        String rawLimit=parameters.getOrDefault("limit","50");
        if(!rawLimit.matches("[1-9][0-9]{0,2}"))throw new IllegalArgumentException("Invalid page size");
        int limit=Integer.parseInt(rawLimit);
        if(limit>100)throw new IllegalArgumentException("Invalid page size");
        return ok(store().list(actor.tenant(),actor.actor(),schoolId,kind,parameters.get("afterId"),limit,parameters.get("termId")));
    }
    @GetMapping("/{kind:organizations|classes}/{id}")
    public ResponseEntity<Map<String,Object>> detail(@PathVariable String kind,@PathVariable String id,
                                                     @RequestParam Map<String,String> parameters) {
        var actor=identity.current();
        if(!parameters.isEmpty())throw new IllegalArgumentException("Unexpected query parameters");
        return ok(store().detail(actor.tenant(),actor.actor(),null,kind,id));
    }
    @PostMapping("/schools")
    public ResponseEntity<Map<String,Object>> school(@RequestBody String body) {
        var actor=identity.current();
        return ok(store().command(actor.tenant(),actor.actor(),null,"schools","create",body(body)));
    }
    @PostMapping("/schools/{schoolId}/{kind:organizations|classes}")
    public ResponseEntity<Map<String,Object>> create(@PathVariable String schoolId,@PathVariable String kind,@RequestBody String body) {
        var actor=identity.current();
        return ok(store().command(actor.tenant(),actor.actor(),schoolId,kind,"create",body(body)));
    }
    @PostMapping("/{kind:organizations|classes}/{id}/rename")
    public ResponseEntity<Map<String,Object>> rename(@PathVariable String kind,@PathVariable String id,@RequestBody String text) {
        var actor=identity.current();var body=new LinkedHashMap<>(body(text));
        if(body.containsKey("id"))throw new IllegalArgumentException("ID comes from route");
        body.put("id",id);
        return ok(store().command(actor.tenant(),actor.actor(),null,kind,"rename",body));
    }
    @GetMapping("/schools/{schoolId}/operations/{kind:organizations|classes}/{action:create|rename}/{intentId}")
    public ResponseEntity<Map<String,Object>> operation(@PathVariable String schoolId,@PathVariable String kind,
            @PathVariable String action,@PathVariable String intentId,@RequestParam Map<String,String> parameters) {
        var actor=identity.current();if(!parameters.isEmpty())throw new IllegalArgumentException("Unexpected query parameters");
        return ok(store().operation(actor.tenant(),actor.actor(),schoolId,kind,action,intentId));
    }
    @GetMapping("/operations/schools/create/{intentId}")
    public ResponseEntity<Map<String,Object>> schoolOperation(@PathVariable String intentId,@RequestParam Map<String,String> parameters) {
        var actor=identity.current();if(!parameters.isEmpty())throw new IllegalArgumentException("Unexpected query parameters");
        return ok(store().operation(actor.tenant(),actor.actor(),null,"schools","create",intentId));
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> body(String text) {
        if(text.length()>16384)throw new IllegalArgumentException("Request too large");
        Object parsed=Json.parse(text);
        if(!(parsed instanceof Map<?,?>))throw new IllegalArgumentException("Object required");
        return (Map<String,Object>)parsed;
    }
    @ExceptionHandler(NativeFoundationIdentity.AuthorityFailure.class)
    public ResponseEntity<Map<String,Object>> identityFailure(NativeFoundationIdentity.AuthorityFailure failure) {
        boundaryAudit(true);
        return error(failure.unavailable?503:403,failure.unavailable?"EDUCATION_AUTHORITY_UNAVAILABLE":"EDUCATION_FORBIDDEN");
    }
    @ExceptionHandler(FcpException.class)
    public ResponseEntity<Map<String,Object>> domain(FcpException failure) {
        int status=switch(failure.code) {
            case "EDUCATION_FORBIDDEN" -> 403;
            case "EDUCATION_NOT_FOUND" -> 404;
            case "EDUCATION_CONFLICT" -> 409;
            case "EDUCATION_INVALID_INPUT", "EDUCATION_INVALID_RELATION" -> 400;
            case "EDUCATION_RESOURCE_LIMIT" -> 429;
            default -> 503;
        };
        return error(status,failure.code);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,Object>> invalid(IllegalArgumentException failure){
        boundaryAudit(false);return error(400,"EDUCATION_INVALID_INPUT");
    }
    private void boundaryAudit(boolean identityFailure) {
        FoundationStore value=stores.getIfAvailable();
        if(value==null)return; // Disabled/uninstalled module has no education event channel.
        try {
            var actor=identity.current();
            if(identityFailure)value.rejectIdentity(actor.tenant(),actor.actor());
            else value.rejectBoundary(actor.tenant(),actor.actor());
        } catch(RuntimeException unavailable) {
            // No trusted identity: never invent a tenant or copy an untrusted request to an event.
            value.boundaryAuditUnavailable();
        }
    }
    @ExceptionHandler(Disabled.class)
    public ResponseEntity<Map<String,Object>> disabled(Disabled failure){return error(503,"EDUCATION_NOT_READY");}
    private static ResponseEntity<Map<String,Object>> error(int status,String error) {
        return ResponseEntity.status(status).body(Map.of("code",status,"msg",error,"error",error));
    }
}
