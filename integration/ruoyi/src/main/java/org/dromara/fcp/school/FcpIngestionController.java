package org.dromara.fcp.school;

import cn.dev33.satoken.annotation.SaCheckPermission;
import dev.fcp.dual.HostResponse;
import dev.fcp.ingestion.IngestionService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Authenticated native host endpoints. No authentication-ignore rule is added. */
@RestController
@RequestMapping(value="/fcp/ingestion",produces=MediaType.APPLICATION_JSON_VALUE)
public class FcpIngestionController {
    private final IngestionService service;
    private final FcpSchoolIdentity identity;
    public FcpIngestionController(IngestionService service,FcpSchoolIdentity identity){this.service=service;this.identity=identity;}
    private ResponseEntity<String> ok(Object data){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(HostResponse.ok(data));}
    @GetMapping("/bootstrap") @SaCheckPermission("fcp:ingest:view")
    public ResponseEntity<String> bootstrap(){return ok(service.describe(identity.current()));}
    @PostMapping("/state") @SaCheckPermission("fcp:ingest:manage")
    public ResponseEntity<String> state(@RequestBody String body){return ok(service.toggle(identity.current(),body));}
    @PostMapping("/imports") @SaCheckPermission("fcp:ingest:import")
    public ResponseEntity<String> imports(@RequestBody String body){return ok(service.ingest(identity.current(),body));}
    @GetMapping("/data/{entity}") @SaCheckPermission("fcp:ingest:view")
    public ResponseEntity<String> data(@PathVariable("entity") String entity,@RequestParam("classId") String classId){return ok(service.read(identity.current(),entity,classId));}
}
