package org.dromara.fcp.plugin;

import fcp.FcpException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;

/** Stable safe exchange errors. Never return CSV contents, SQL, credentials or exception stacks. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=FcpExchangeController.class)
public final class FcpExchangeAdvice {
    @ExceptionHandler(FcpException.class) public ResponseEntity<Map<String,Object>> rejected(FcpException e){
        int status=switch(e.code){
            case "UNAUTHENTICATED" -> 401;
            case "PERMISSION_DENIED","USER_REQUIRED","TASK_IDENTITY_REQUIRED","DOWNLOAD_DENIED","FIELD_DENIED" -> 403;
            case "NOT_FOUND","CONFIG_NOT_FOUND" -> 404;
            case "STALE_PREVIEW","PREVIEW_EXPIRED","VALIDATION_FAILED","CONFLICT","IDEMPOTENCY_CONFLICT","VERSION_CONFLICT","ADMISSION_CLOSED" -> 409;
            case "SOURCE_LIMIT","CURSOR_LIMIT" -> 413;
            case "INVALID_ARGUMENT","INVALID_INPUT","INVALID_ENCODING","INVALID_SOURCE","IDEMPOTENCY_REQUIRED" -> 400;
            case "DEADLINE_EXCEEDED","DEADLINE" -> 504;
            default -> 503;
        };return response(status,e.code);
    }
    @ExceptionHandler({IllegalArgumentException.class,ClassCastException.class}) public ResponseEntity<Map<String,Object>> invalid(Exception e){return response(400,"INVALID_ARGUMENT");}
    @ExceptionHandler(IOException.class) public ResponseEntity<Map<String,Object>> io(IOException e){return response(503,"EXCHANGE_UNAVAILABLE");}
    private ResponseEntity<Map<String,Object>> response(int status,String code){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("code",status,"msg","Exchange request rejected","data",Map.of("fcpCode",code,"traceId",UUID.randomUUID().toString())));}
}
