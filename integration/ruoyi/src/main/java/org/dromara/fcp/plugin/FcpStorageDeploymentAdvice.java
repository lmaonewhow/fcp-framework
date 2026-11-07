package org.dromara.fcp.plugin;

import fcp.FcpException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=FcpStorageDeploymentController.class)
public final class FcpStorageDeploymentAdvice {
    @ExceptionHandler(FcpException.class) public ResponseEntity<Map<String,Object>> handle(FcpException e){int status=switch(e.code){case "UNAUTHENTICATED"->401;case "PERMISSION_DENIED"->403;case "NOT_FOUND"->404;case "INVALID_ARGUMENT"->400;case "PLAN_EXPIRED","PLAN_STALE","CONFIRMATION_REQUIRED","IDEMPOTENCY_CONFLICT","VERSION_CONFLICT"->409;case "BUSY"->429;default->503;};String message=e.code.equals("DEPLOYMENT_NOT_CONFIGURED")?"Storage migrations are disabled; configure a separate server-side deployment identity":status>=500?"Storage deployment unavailable or outcome uncertain; inspect the storage operation receipt":e.getMessage();return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code",status,"msg",message,"data",Map.of("fcpCode",e.code)));}
    @ExceptionHandler({IllegalArgumentException.class,ClassCastException.class}) public ResponseEntity<Map<String,Object>> invalid(Exception ignored){return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("code",400,"msg","Invalid storage deployment request","data",Map.of("fcpCode","INVALID_ARGUMENT")));}
    @ExceptionHandler(IOException.class) public ResponseEntity<Map<String,Object>> unavailable(IOException ignored){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(Map.of("code",503,"msg","Unable to verify package or storage operation receipt","data",Map.of("fcpCode","STORAGE_UNAVAILABLE")));}
}
