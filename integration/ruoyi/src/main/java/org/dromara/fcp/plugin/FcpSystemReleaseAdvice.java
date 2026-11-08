package org.dromara.fcp.plugin;
import fcp.FcpException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Map;
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={FcpSystemReleaseController.class,FcpReleaseObservationController.class})
public final class FcpSystemReleaseAdvice {
    private ResponseEntity<Map<String,Object>> error(int status,String code,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("code",status,"msg",message,"data",Map.of("fcpCode",code)));}
    @ExceptionHandler(FcpException.class) public ResponseEntity<Map<String,Object>> known(FcpException e){int status=switch(e.code){case "UNAUTHENTICATED"->401;case "PERMISSION_DENIED","FORBIDDEN"->403;case "INVALID_ARGUMENT"->400;default->409;};return error(status,e.code,e.getMessage());}
    @ExceptionHandler({IllegalArgumentException.class,ClassCastException.class,ArithmeticException.class}) public ResponseEntity<Map<String,Object>> invalid(Exception ignored){return error(400,"INVALID_ARGUMENT","Invalid release request");}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<Map<String,Object>> conflict(Exception ignored){return error(409,"RELEASE_CONFLICT","Release state changed or operation is unsupported; refresh and review");}
    @ExceptionHandler(IOException.class) public ResponseEntity<Map<String,Object>> unavailable(Exception ignored){return error(503,"RELEASE_RECONCILE_REQUIRED","Release outcome or preflight requires reconciliation; do not retry a write blindly");}
}
