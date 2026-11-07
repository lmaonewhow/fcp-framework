package org.dromara.fcp.plugin;
import fcp.FcpException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=FcpPluginVersionController.class)
public final class FcpPluginVersionAdvice {
    @ExceptionHandler(FcpException.class) public ResponseEntity<Map<String,Object>> handle(FcpException error){int status=switch(error.code){case "UNAUTHENTICATED"->401;case "PERMISSION_DENIED"->403;case "INVALID_ARGUMENT","INVALID_VERSION_CHANGE"->400;case "DEADLINE_EXCEEDED"->504;case "PACKAGE_LIMIT","BUSY"->429;case "OUTCOME_UNKNOWN","JOURNAL_UNAVAILABLE","DATA_ACCESS"->503;default->409;};return ResponseEntity.status(status).body(Map.of("code",status,"msg",status>=500?"Version outcome requires receipt reconciliation":error.getMessage(),"data",Map.of("fcpCode",error.code)));}
    @ExceptionHandler({IllegalArgumentException.class,ClassCastException.class}) public ResponseEntity<Map<String,Object>> invalid(Exception error){return ResponseEntity.badRequest().body(Map.of("code",400,"msg","Invalid version request","data",Map.of("fcpCode","INVALID_ARGUMENT")));}
}
