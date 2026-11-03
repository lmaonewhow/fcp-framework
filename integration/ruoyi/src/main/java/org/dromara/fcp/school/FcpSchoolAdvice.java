package org.dromara.fcp.school;
import fcp.FcpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={FcpSchoolController.class,FcpUiController.class})
public class FcpSchoolAdvice {
    private static final Logger LOG=LoggerFactory.getLogger(FcpSchoolAdvice.class);
    @ExceptionHandler(FcpException.class)
    public ResponseEntity<Map<String,Object>> handle(FcpException e){
        int status=switch(e.code){case "UNAUTHENTICATED"->401;case "FORBIDDEN"->403;
            case "NOT_FOUND","OPERATION_MISSING","CAPABILITY_MISSING"->404;
            case "STALE_COMPOSITION","IDENTITY_CHANGED","IDEMPOTENCY_CONFLICT","RECORD_CONFLICT","PLUGIN_DISABLED"->409;
            case "INVALID_INPUT","BUSINESS_RULE","RESULT_LIMIT"->400;case "DEADLINE"->504;
            case "BUSY","COMMIT_UNKNOWN"->503;default->500;};
        String trace=UUID.randomUUID().toString();if(status>=500)LOG.error("FCP school trace={} code={}",trace,e.code,e);
        return ResponseEntity.status(status).body(Map.of("code",status,"msg",status>=500?"FCP execution failed; inspect the server trace":e.getMessage(),
                "data",Map.of("fcpCode",e.code,"traceId",trace)));
    }
}
