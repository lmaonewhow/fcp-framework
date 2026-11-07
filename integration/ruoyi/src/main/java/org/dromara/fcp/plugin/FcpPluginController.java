package org.dromara.fcp.plugin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import dev.fcp.dual.HostResponse;
import dev.fcp.host.PluginApplication;
import dev.fcp.host.PluginGrantAdmin;
import fcp.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static fcp.Schema.*;

@RestController
@ConditionalOnProperty(name="fcp.runtime.enabled",havingValue="true")
@RequestMapping(value="/fcp",produces=MediaType.APPLICATION_JSON_VALUE)
public final class FcpPluginController {
    private final NativePluginHost identity;private final PluginApplication app;private final PluginGrantAdmin grants;
    public FcpPluginController(NativePluginHost identity,PluginApplication app,PluginGrantAdmin grants){this.identity=identity;this.app=app;this.grants=grants;}
    /** Header-authenticated JSON/binary API: cookie-only requests cannot mutate plugin state. */
    @ModelAttribute public void requireExplicitToken(HttpServletRequest request) {
        String header=request.getHeader("Authorization");
        if(header==null||header.isBlank())throw new FcpException("UNAUTHENTICATED","Explicit native authorization header required");
        String presented=header.startsWith("Bearer ")?header.substring(7):header;
        String authenticated=cn.dev33.satoken.stp.StpUtil.getTokenValue();
        if(authenticated==null||!java.security.MessageDigest.isEqual(presented.getBytes(java.nio.charset.StandardCharsets.UTF_8),authenticated.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            throw new FcpException("UNAUTHENTICATED","Native authorization header mismatch");
    }
    private ResponseEntity<String> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(HostResponse.ok(value));}
    @PostMapping(value="/packages/staged",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE) @SaCheckPermission("fcp:plugin:install")
    public ResponseEntity<String> stage(HttpServletRequest request,@RequestHeader("X-Fcp-Digest") String digest,
            @RequestHeader("Idempotency-Key") String key)throws Exception {
        return ok(app.stage(identity.current(),request.getInputStream(),digest,key));
    }
    @PostMapping(value="/packages",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE) @SaCheckPermission("fcp:plugin:install")
    public ResponseEntity<String> install(HttpServletRequest request,@RequestHeader("X-Fcp-Digest") String digest,
            @RequestHeader("Idempotency-Key") String key,@RequestHeader("X-Fcp-Revision") long revision)throws Exception {
        return ok(app.install(identity.current(),request.getInputStream(),digest,key,revision));
    }
    @GetMapping("/plugins") @SaCheckPermission("fcp:plugin:view")
    public ResponseEntity<String> list(){return ok(app.list(identity.current()));}
    @GetMapping("/plugins/{plugin}") @SaCheckPermission("fcp:plugin:view")
    public ResponseEntity<String> status(@PathVariable String plugin){return ok(app.status(identity.current(),plugin));}
    @PostMapping("/plugins/{plugin}/uninstall") @SaCheckPermission("fcp:plugin:install")
    public ResponseEntity<String> uninstall(@PathVariable String plugin,@RequestBody String text){var b=body(text,Set.of("expectedRevision","confirmedDigest"));return ok(app.uninstall(identity.current(),plugin,str(b.get("confirmedDigest")),number(b.get("expectedRevision"))));}
    @GetMapping("/plugins/{plugin}/grant-options") @SaCheckPermission("fcp:plugin:install")
    public ResponseEntity<String> grantOptions(@PathVariable String plugin,@RequestParam(required=false) String targetTenant){var c=identity.current();return ok(grants.options(c,plugin,targetTenant==null?c.tenantId():targetTenant));}
    @PostMapping("/plugins/{plugin}/grants/plans") @SaCheckPermission("fcp:plugin:install")
    public ResponseEntity<String> grantPlan(@PathVariable String plugin,@RequestBody String body){var b=body(body,Set.of("grants","expectedRevision","targetTenant"));var c=identity.current();List<Map<String,Object>> changes=new ArrayList<>();for(Object item:arr(b,"grants"))changes.add(map(item));return ok(grants.plan(c,plugin,b.containsKey("targetTenant")?str(b.get("targetTenant")):c.tenantId(),number(b.get("expectedRevision")),changes));}
    @PostMapping("/plugins/{plugin}/plans") @SaCheckPermission("fcp:plugin:manage")
    public ResponseEntity<String> plan(@PathVariable String plugin,@RequestBody String body){var b=body(body,Set.of("action","expectedRevision"));return ok(app.plan(identity.current(),plugin,str(b.get("action")),number(b.get("expectedRevision"))));}
    @PostMapping("/plans/{plan}/confirm") @SaCheckPermission(value={"fcp:plugin:manage","fcp:plugin:install"},mode=SaMode.OR)
    public ResponseEntity<String> confirm(@PathVariable String plan,@RequestBody String body){var b=body(body,Set.of("digest","affectedPlugins","idempotencyKey"));Set<String> affected=new HashSet<>();for(Object item:arr(b,"affectedPlugins"))affected.add(str(item));return ok(plan.startsWith("grant-")?grants.confirm(identity.current(),plan,str(b.get("digest")),affected,str(b.get("idempotencyKey"))):app.confirm(identity.current(),plan,str(b.get("digest")),affected,str(b.get("idempotencyKey"))));}
    @GetMapping("/operations/{operation}") @SaCheckPermission(value={"fcp:plugin:view","fcp:plugin:install"},mode=SaMode.OR)
    public ResponseEntity<String> operation(@PathVariable String operation){return ok(operation.startsWith("grant.")?grants.operation(identity.current(),operation):app.operation(identity.current(),operation));}
    @PostMapping("/operations/{operation}/decision") @SaCheckPermission("fcp:plugin:manage")
    public ResponseEntity<String> decision(@PathVariable String operation,@RequestBody String body){var b=body(body,Set.of("decision"));return ok(app.decide(identity.current(),operation,str(b.get("decision"))));}
    @GetMapping("/ui/catalog") @SaCheckPermission("fcp:plugin:view")
    public ResponseEntity<String> catalog(){return ok(app.catalog(identity.current()));}
    @PostMapping("/capabilities/{capability}/invoke") @SaCheckPermission("fcp:plugin:view")
    public ResponseEntity<String> invoke(@PathVariable String capability,@RequestBody String body){var b=body(body,Set.of("input","expectedRevision","idempotencyKey"));return ok(app.invoke(identity.current(),capability,map(b.get("input")),(String)b.get("idempotencyKey"),number(b.get("expectedRevision"))));}
    private static Map<String,Object> body(String text,Set<String> allowed){if(text.length()>1024*1024)throw new FcpException("INVALID_ARGUMENT","Request body too large");var value=map(Json.parse(text));if(!allowed.containsAll(value.keySet()))throw new FcpException("INVALID_ARGUMENT","Unknown request fields");return value;}
}
