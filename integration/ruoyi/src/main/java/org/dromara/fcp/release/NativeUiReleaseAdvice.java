package org.dromara.fcp.release;
import fcp.FcpException;
import java.io.*;
import java.util.Map;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=NativeUiReleaseController.class)
public class NativeUiReleaseAdvice {
    private ResponseEntity<Map<String,Object>> error(int status,String code){return ResponseEntity.status(status).header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").body(Map.of("code",status,"msg","UI release unavailable","data",Map.of("fcpCode",code)));}
    @ExceptionHandler(FileNotFoundException.class) public ResponseEntity<Map<String,Object>> missing(){return error(404,"UI_ASSET_NOT_FOUND");}
    @ExceptionHandler(IOException.class) public ResponseEntity<Map<String,Object>> unavailable(){return error(503,"UI_ASSET_UNVERIFIED");}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,Object>> invalid(){return error(400,"INVALID_ARGUMENT");}
    @ExceptionHandler(FcpException.class) public ResponseEntity<Map<String,Object>> known(FcpException e){return error(e.code.equals("UNAUTHENTICATED")?401:(e.code.equals("PERMISSION_DENIED")||e.code.equals("FORBIDDEN"))?403:409,e.code);}
}
