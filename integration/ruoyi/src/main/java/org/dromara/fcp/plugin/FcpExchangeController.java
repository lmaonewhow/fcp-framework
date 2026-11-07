package org.dromara.fcp.plugin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import dev.fcp.dual.HostResponse;
import fcp.*;
import fcp.exchange.ExchangeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static fcp.Schema.*;

/** Native authenticated fixture exchange. No request can provide tenant/principal/URL/configuration. */
@RestController
@ConditionalOnProperty(name={"fcp.runtime.enabled","fcp.exchange.enabled"},havingValue="true")
@RequestMapping(value="/fcp/exchange",produces=MediaType.APPLICATION_JSON_VALUE)
public final class FcpExchangeController {
    private final NativePluginHost identity;private final ExchangeService exchange;
    public FcpExchangeController(NativePluginHost identity,ExchangeService exchange){this.identity=identity;this.exchange=exchange;}
    @ModelAttribute public void requireExplicitToken(HttpServletRequest request){
        StpUtil.checkLogin();String header=request.getHeader("Authorization");
        if(header==null||header.isBlank())throw new FcpException("UNAUTHENTICATED","Explicit native authorization header required");
        String presented=header.startsWith("Bearer ")?header.substring(7):header,authenticated=StpUtil.getTokenValue();
        if(authenticated==null||!MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),authenticated.getBytes(StandardCharsets.UTF_8)))throw new FcpException("UNAUTHENTICATED","Native authorization header mismatch");
    }
    private ResponseEntity<String> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(HostResponse.ok(value));}
    @PostMapping(value="/configs/{config}/preview",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE) @SaCheckPermission("fcp:plugin:view")
    public ResponseEntity<String> preview(@PathVariable String config,HttpServletRequest request)throws Exception {
        // File name never becomes a filesystem path. Content is bounded/UTF-8 decoded by ExchangeService.
        return ok(exchange.preview(identity.current(),config,"uploaded-csv",request.getInputStream()));
    }
    @PostMapping("/configs/{config}/preview-api") @SaCheckPermission("fcp:plugin:view")
    public ResponseEntity<String> previewApi(@PathVariable String config,@RequestBody String text)throws Exception {
        var b=body(text,Set.of("cursor"));return ok(exchange.previewConnector(identity.current(),config,b.containsKey("cursor")?str(b.get("cursor")):""));
    }
    @PostMapping("/previews/{preview}/confirm") @SaCheckPermission("fcp:plugin:view")
    public ResponseEntity<String> confirm(@PathVariable String preview,@RequestBody String text)throws Exception {
        var b=body(text,Set.of("digest","idempotencyKey"));return ok(exchange.confirm(identity.current(),preview,str(b.get("digest")),str(b.get("idempotencyKey"))));
    }
    @PostMapping("/configs/{config}/exports") @SaCheckPermission("fcp:plugin:view")
    public ResponseEntity<String> export(@PathVariable String config,@RequestBody String text)throws Exception {
        var b=body(text,Set.of("fields"));Set<String> fields=new LinkedHashSet<>();for(Object f:arr(b,"fields"))if(!fields.add(str(f)))throw new FcpException("INVALID_ARGUMENT","Duplicate field");return ok(exchange.export(identity.current(),config,fields));
    }
    @GetMapping(value="/downloads/{handle}",produces="text/csv") @SaCheckPermission("fcp:plugin:view")
    public ResponseEntity<byte[]> download(@PathVariable String handle){
        byte[] bytes=exchange.download(identity.current(),handle);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"fcp-fixture-export.csv\"")
            .contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).body(bytes);
    }
    private static Map<String,Object> body(String text,Set<String> fields){if(text.length()>4096)throw new FcpException("INVALID_ARGUMENT","Request too large");var b=map(Json.parse(text));if(!fields.containsAll(b.keySet()))throw new FcpException("INVALID_ARGUMENT","Unknown request field");return b;}
}
