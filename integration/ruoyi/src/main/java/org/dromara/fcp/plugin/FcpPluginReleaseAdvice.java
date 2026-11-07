package org.dromara.fcp.plugin;
import fcp.FcpException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Map;
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=FcpPluginReleaseController.class)
public final class FcpPluginReleaseAdvice {
    @ExceptionHandler(FcpException.class) public ResponseEntity<Map<String,Object>> error(FcpException e){int status=switch(e.code){case "PERMISSION_DENIED","FORBIDDEN"->403;case "UNAUTHENTICATED"->401;case "INVALID_ARGUMENT"->400;case "DATA_ACCESS","OUTCOME_UNKNOWN","VERSION_GRANT_OUTCOME_UNKNOWN","PACKAGE_INTEGRITY"->503;default->409;};return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("code",status,"msg",status>=500?"Plugin release operation requires reconciliation":e.getMessage(),"data",Map.of("fcpCode",e.code)));}
    @ExceptionHandler({IllegalArgumentException.class,ClassCastException.class,java.time.DateTimeException.class}) public ResponseEntity<Map<String,Object>> invalid(Exception ignored){return ResponseEntity.badRequest().body(Map.of("code",400,"msg","Invalid plugin release request"));}
    @ExceptionHandler(IOException.class) public ResponseEntity<Map<String,Object>> unavailable(Exception ignored){return ResponseEntity.status(503).body(Map.of("code",503,"msg","Plugin release outcome requires reconciliation"));}
}
