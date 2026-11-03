package org.dromara.fcp.school;

import cn.dev33.satoken.annotation.SaCheckPermission;
import dev.fcp.host.UiRoutePolicy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/fcp/ui")
public class FcpUiController {
    private final FcpSchoolIdentity identity;
    public FcpUiController(FcpSchoolIdentity identity) { this.identity = identity; }
    @GetMapping("/session") @SaCheckPermission("fcp:eval:list")
    public ResponseEntity<Map<String,Object>> session() {
        return ResponseEntity.ok(Map.of("code",200,"msg","success","data",UiRoutePolicy.session(identity.current())));
    }
    @GetMapping("/soybean-routes") @SaCheckPermission("fcp:eval:list")
    public ResponseEntity<Map<String,Object>> routes() {
        return ResponseEntity.ok(Map.of("code",200,"msg","success","data",UiRoutePolicy.soybeanRoutes(identity.current())));
    }
}
