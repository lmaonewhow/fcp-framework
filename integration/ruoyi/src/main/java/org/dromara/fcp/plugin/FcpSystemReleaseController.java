package org.dromara.fcp.plugin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import dev.fcp.dual.HostResponse;
import dev.fcp.host.SystemVersionApplication;
import fcp.*;
import fcp.release.control.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static fcp.Schema.*;

@RestController
@ConditionalOnProperty(name="fcp.release.enabled",havingValue="true")
@RequestMapping(value="/fcp/releases",produces=MediaType.APPLICATION_JSON_VALUE)
public final class FcpSystemReleaseController {
    private final NativePluginHost identity;private final SystemVersionApplication versions;private final ReleaseController releases;
    public FcpSystemReleaseController(NativePluginHost identity,SystemVersionApplication versions,ReleaseController releases){this.identity=identity;this.versions=versions;this.releases=releases;}
    @ModelAttribute public void explicitNativeHeader(HttpServletRequest request){String header=request.getHeader("Authorization");if(header==null||header.isBlank())throw new FcpException("UNAUTHENTICATED","Explicit native authorization header required");String token=header.startsWith("Bearer ")?header.substring(7):header;String authenticated=StpUtil.getTokenValue();if(authenticated==null||!java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),authenticated.getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new FcpException("UNAUTHENTICATED","Native header mismatch");}
    private ResponseEntity<String> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(HostResponse.ok(value));}
    @GetMapping("/versions") @SaCheckPermission("fcp:release:view") public ResponseEntity<String> versions(){return ok(versions.list(identity.current()));}
    @PostMapping("/artifacts") @SaCheckPermission("fcp:release:manage") public ResponseEntity<String> artifact(@RequestBody String text){return ok(versions.registerArtifact(identity.current(),body(text)));}
    @PostMapping("/versions") @SaCheckPermission("fcp:release:manage") public ResponseEntity<String> register(@RequestBody String text){return ok(versions.registerRelease(identity.current(),body(text)));}
    @GetMapping("/state") @SaCheckPermission("fcp:release:view") public ResponseEntity<String> state()throws Exception{return ok(releases.snapshot(identity.current()));}
    @PostMapping("/plans") @SaCheckPermission("fcp:release:manage") public ResponseEntity<String> plan(@RequestBody String text)throws Exception{var b=fields(text,Set.of("expectedRevision","operationId","plan"));return ok(releases.createPlan(identity.current(),number(b.get("expectedRevision")),str(b.get("operationId")),ReleasePlan.parse(map(b.get("plan")))));}
    @PostMapping("/operations") @SaCheckPermission("fcp:release:manage") public ResponseEntity<String> execute(@RequestBody String text)throws Exception{var b=fields(text,Set.of("expectedRevision","operationId","action","basisPoints"));return ok(releases.execute(identity.current(),number(b.get("expectedRevision")),str(b.get("operationId")),DeploymentProvider.Action.valueOf(str(b.get("action"))),Math.toIntExact(number(b.get("basisPoints")))));}
    @PostMapping("/recover") @SaCheckPermission("fcp:release:manage") public ResponseEntity<String> recover(@RequestBody String text)throws Exception{var b=fields(text,Set.of("expectedRevision"));return ok(releases.recover(identity.current(),number(b.get("expectedRevision"))));}
    @PostMapping("/reconcile") @SaCheckPermission("fcp:release:manage") public ResponseEntity<String> reconcile(@RequestBody String text)throws Exception{fields(text,Set.of());return ok(releases.reconcile(identity.current()));}
    private static Map<String,Object> body(String text){if(text.length()>1024*1024)throw new IllegalArgumentException("Release request limit");return map(Json.parse(text));}
    private static Map<String,Object> fields(String text,Set<String> keys){var b=body(text);if(!b.keySet().equals(keys))throw new IllegalArgumentException("Release request schema");return b;}
}
