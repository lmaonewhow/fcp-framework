package org.dromara.fcp.education;

import fcp.FcpException;
import fcp.education.RosterStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Native session only. There is deliberately no person route or mutation route. */
@RestController
@RequestMapping("/fcp/education/roster")
public final class RosterController {
    private final NativeFoundationIdentity identity;
    private final ObjectProvider<RosterStore> stores;
    public RosterController(NativeFoundationIdentity identity,ObjectProvider<RosterStore> stores){this.identity=identity;this.stores=stores;}
    private static ResponseEntity<Map<String,Object>> ok(Object data){return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("code",200,"msg","","data",data));}
    private RosterStore store(){RosterStore store=stores.getIfAvailable();if(store==null)throw new FcpException("EDUCATION_NOT_READY","Roster is not enabled");return store;}
    @GetMapping("/capabilities")
    public ResponseEntity<Map<String,Object>> capabilities(@RequestParam MultiValueMap<String,String> parameters) {
        var actor=identity.current();RosterStore.page(parameters,false);RosterStore store=stores.getIfAvailable();
        if(store==null){identity.requireDiscovery("education:roster:view");return ok(RosterStore.blocked());}
        return ok(store.capabilities(actor.tenant(),actor.actor()));
    }
    @GetMapping("/schools")
    public ResponseEntity<Map<String,Object>> schools(@RequestParam MultiValueMap<String,String> parameters) {
        var actor=identity.current();var page=RosterStore.page(parameters,true);
        return ok(store().schools(actor.tenant(),actor.actor(),page.afterId(),page.limit()));
    }
    @GetMapping("/schools/{schoolId}/{kind:enrollments|memberships|assignments}")
    public ResponseEntity<Map<String,Object>> list(@PathVariable String schoolId,@PathVariable String kind,@RequestParam MultiValueMap<String,String> parameters) {
        var actor=identity.current();var page=RosterStore.page(parameters,true);
        return ok(store().list(actor.tenant(),actor.actor(),schoolId,kind,page.afterId(),page.limit()));
    }
    @GetMapping("/schools/{schoolId}/{kind:enrollments|memberships|assignments}/{id}")
    public ResponseEntity<Map<String,Object>> detail(@PathVariable String schoolId,@PathVariable String kind,@PathVariable String id,@RequestParam MultiValueMap<String,String> parameters) {
        var actor=identity.current();RosterStore.page(parameters,false);
        return ok(store().detail(actor.tenant(),actor.actor(),schoolId,kind,id));
    }
    @ExceptionHandler(NativeFoundationIdentity.AuthorityFailure.class)
    public ResponseEntity<Map<String,Object>> identityFailure(NativeFoundationIdentity.AuthorityFailure failure){boundary(true);return error(failure.unavailable?503:403,failure.unavailable?"EDUCATION_AUTHORITY_UNAVAILABLE":"EDUCATION_FORBIDDEN");}
    @ExceptionHandler(FcpException.class)
    public ResponseEntity<Map<String,Object>> domain(FcpException failure) {
        // Parser errors occur before the store transaction; record their minimal trusted context here.
        if(failure.code.equals("EDUCATION_INVALID_INPUT"))boundary(false);
        return error(switch(failure.code){case "EDUCATION_FORBIDDEN"->403;case "EDUCATION_NOT_FOUND"->404;case "EDUCATION_INVALID_INPUT","EDUCATION_INVALID_RELATION"->400;case "EDUCATION_RESOURCE_LIMIT"->429;default->503;},failure.code);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,Object>> invalid(IllegalArgumentException failure){boundary(false);return error(400,"EDUCATION_INVALID_INPUT");}
    private void boundary(boolean identityFailure) {
        RosterStore store=stores.getIfAvailable();if(store==null)return;
        try{var actor=identity.current();if(identityFailure)store.rejectIdentity(actor.tenant(),actor.actor());else store.rejectBoundary(actor.tenant(),actor.actor());}
        catch(RuntimeException unavailable){store.boundaryAuditUnavailable();}
    }
    private static ResponseEntity<Map<String,Object>> error(int status,String code){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("code",status,"msg",code,"error",code));}
}
