package org.dromara.fcp.plugin;

import fcp.FcpException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=FcpPluginController.class)
public final class FcpPluginAdvice {
    @ExceptionHandler(FcpException.class)
    public ResponseEntity<Map<String,Object>> handle(FcpException e) {
        int status=switch(e.code){case "UNAUTHENTICATED"->401;case "PERMISSION_DENIED","FORBIDDEN","STALE_RELEASE_AFFINITY","RELEASE_DISABLED"->403;case "INVALID_ARGUMENT","MANIFEST","SCHEMA","TYPE","HOST_IMPLEMENTATION_DEPENDENCY","PLUGIN_DEPENDENCY_METADATA"->400;
            case "MODE_CONFLICT","VERSION_CONFLICT","STALE_COMPOSITION","IDEMPOTENCY_CONFLICT","PLAN_EXPIRED","CONFIRMATION_REQUIRED","PLUGIN_DISABLED"->409;
            case "DEADLINE_EXCEEDED","DEADLINE"->504;case "BUSY","PACKAGE_LIMIT"->429;default->503;};
        String trace=UUID.randomUUID().toString();
        return ResponseEntity.status(status).body(Map.of("code",status,"msg",status>=500?"Plugin operation unavailable; use the operation receipt to reconcile":e.getMessage(),"data",Map.of("fcpCode",e.code,"traceId",trace)));
    }
    @ExceptionHandler(java.io.IOException.class)
    public ResponseEntity<Map<String,Object>> unavailable(java.io.IOException ignored) {
        return ResponseEntity.status(503).header("Cache-Control","no-store").body(Map.of("code",503,"msg","Plugin release operation requires reconciliation","data",Map.of("fcpCode","RELEASE_UNAVAILABLE")));
    }
    @ExceptionHandler({IllegalArgumentException.class,ClassCastException.class})
    public ResponseEntity<Map<String,Object>> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(Map.of("code",400,"msg","Invalid plugin request","data",Map.of("fcpCode","INVALID_ARGUMENT")));
    }
}
