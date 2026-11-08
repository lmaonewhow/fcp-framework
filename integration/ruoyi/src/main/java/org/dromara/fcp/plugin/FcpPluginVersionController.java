package org.dromara.fcp.plugin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import dev.fcp.dual.HostResponse;
import dev.fcp.host.PluginVersionApplication;
import fcp.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import dev.fcp.host.PluginReleaseApplication;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static fcp.Schema.*;

@RestController
@ConditionalOnProperty(name="fcp.runtime.enabled",havingValue="true")
@RequestMapping(value="/fcp",produces=MediaType.APPLICATION_JSON_VALUE)
@SaCheckPermission("fcp:plugin:install")
public final class FcpPluginVersionController {
    private final NativePluginHost identity;private final PluginVersionApplication application;private final ObjectProvider<PluginReleaseApplication> releases;
    public FcpPluginVersionController(NativePluginHost identity,PluginVersionApplication application,ObjectProvider<PluginReleaseApplication> releases){this.identity=identity;this.application=application;this.releases=releases;}
    @ModelAttribute public void explicitNativeHeader(HttpServletRequest request){String header=request.getHeader("Authorization");if(header==null||header.isBlank())throw new FcpException("UNAUTHENTICATED","Explicit native authorization header required");String token=header.startsWith("Bearer ")?header.substring(7):header;String authenticated=StpUtil.getTokenValue();if(authenticated==null||!java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),authenticated.getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new FcpException("UNAUTHENTICATED","Native header mismatch");}
    private void legacyMutation(){if(releases.getIfAvailable()!=null)throw new FcpException("MODE_CONFLICT","Use version-bound plugin release management in release mode");}
    private ResponseEntity<String> ok(Object value){return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(HostResponse.ok(value));}
    @GetMapping("/plugins/{plugin}/versions")
    public ResponseEntity<String> versions(@PathVariable String plugin)throws Exception{return ok(application.versions(identity.current(),plugin));}
    @PostMapping("/plugins/{plugin}/version-plans")
    public ResponseEntity<String> plan(@PathVariable String plugin,@RequestBody String text)throws Exception{legacyMutation();var body=body(text,Set.of("action","targetVersion","targetDigest","expectedRevision"));return ok(application.plan(identity.current(),plugin,str(body.get("action")),str(body.get("targetVersion")),str(body.get("targetDigest")),number(body.get("expectedRevision"))));}
    @PostMapping("/version-plans/{plan}/confirm")
    public ResponseEntity<String> confirm(@PathVariable String plan,@RequestBody String text)throws Exception{legacyMutation();var body=body(text,Set.of("digest","idempotencyKey","confirmedDowntime"));if(!(body.get("confirmedDowntime") instanceof Boolean confirmed))throw new FcpException("INVALID_ARGUMENT","confirmedDowntime boolean required");return ok(application.confirm(identity.current(),plan,str(body.get("digest")),str(body.get("idempotencyKey")),confirmed));}
    @GetMapping("/version-operations/{operation}")
    public ResponseEntity<String> operation(@PathVariable String operation){return ok(application.operation(identity.current(),operation));}
    private static Map<String,Object> body(String text,Set<String> allowed){if(text.length()>1024*1024)throw new FcpException("INVALID_ARGUMENT","Version request too large");var body=map(Json.parse(text));if(!body.keySet().equals(allowed))throw new FcpException("INVALID_ARGUMENT","Missing or unknown version request fields");return body;}
}
