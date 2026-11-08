package org.dromara.fcp.plugin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import dev.fcp.dual.HostResponse;
import dev.fcp.host.*;
import fcp.*;
import fcp.host.TrustedContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
import static fcp.Schema.*;

@RestController
@ConditionalOnProperty(name="fcp.plugin.release.enabled",havingValue="true")
@RequestMapping(value="/fcp/plugin-releases",produces=MediaType.APPLICATION_JSON_VALUE)
@SaCheckPermission("fcp:release:manage")
public final class FcpPluginReleaseController {
    private final NativePluginHost identity;private final PluginReleaseApplication application;
    public FcpPluginReleaseController(NativePluginHost identity,PluginReleaseApplication application){this.identity=identity;this.application=application;}
    @ModelAttribute public void explicitToken(HttpServletRequest request){String h=request.getHeader("Authorization");if(h==null||h.isBlank())throw new FcpException("UNAUTHENTICATED","Native token header required");String token=h.startsWith("Bearer ")?h.substring(7):h;String authenticated=StpUtil.getTokenValue();if(authenticated==null||!java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),authenticated.getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new FcpException("UNAUTHENTICATED","Native authorization mismatch");}
    private ResponseEntity<String> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(HostResponse.ok(value));}
    @GetMapping public ResponseEntity<String> state()throws Exception{return ok(application.state(identity.current()));}
    @PostMapping public ResponseEntity<String> register(@RequestBody String text)throws Exception{var b=body(text,Set.of("release","expectedRevision"));return ok(application.register(identity.current(),map(b.get("release")),number(b.get("expectedRevision"))));}
    @GetMapping("/{release}/status") public ResponseEntity<String> status(@PathVariable String release)throws Exception{return ok(application.status(identity.current(),release));}
    @PostMapping("/{release}/drain") public ResponseEntity<String> drain(@PathVariable String release,@RequestBody String text)throws Exception{var b=body(text,Set.of("expectedRevision"));return ok(application.beginDrain(identity.current(),release,number(b.get("expectedRevision"))));}
    @PostMapping("/{release}/activate") public ResponseEntity<String> activate(@PathVariable String release,@RequestBody String text)throws Exception{var b=body(text,Set.of("expectedRevision"));return ok(application.activate(identity.current(),release,number(b.get("expectedRevision"))));}
    @PostMapping("/{release}/disable") public ResponseEntity<String> disable(@PathVariable String release,@RequestBody String text)throws Exception{var b=body(text,Set.of("expectedRevision"));return ok(application.disable(identity.current(),release,number(b.get("expectedRevision"))));}
    @PostMapping("/{release}/stop") public ResponseEntity<String> stop(@PathVariable String release,@RequestBody String text)throws Exception{body(text,Set.of());return ok(application.stop(identity.current(),release));}
    @PostMapping("/tasks/{key}/cancel") public ResponseEntity<String> cancelTask(@PathVariable String key,@RequestBody String text)throws Exception{var b=body(text,Set.of("expectedRevision","reason","confirmedTaskKey"));if(!key.equals(str(b.get("confirmedTaskKey"))))throw new FcpException("CONFIRMATION_REQUIRED","Exact task key confirmation required");return ok(application.cancelTask(identity.current(),key,number(b.get("expectedRevision")),str(b.get("reason"))));}
    @PostMapping("/grant-options") public ResponseEntity<String> options(@RequestBody String text)throws Exception{
        var b=body(text,Set.of("pluginId","version","digest","targetTenant","dataScope","principalKind","principalId"));
        return ok(application.grantOptions(identity.current(),version(b),str(b.get("targetTenant")),str(b.get("dataScope")),TrustedContext.Kind.valueOf(str(b.get("principalKind"))),str(b.get("principalId"))));
    }
    @PostMapping("/grants") public ResponseEntity<String> grant(@RequestBody String text){
        var b=body(text,Set.of("pluginId","version","digest","confirmedDigest","targetTenant","dataScope","principalKind","principalId","capability","operation","allow","expiresAt","expectedRevision"));
        if(!str(b.get("digest")).equals(str(b.get("confirmedDigest"))))throw new FcpException("CONFIRMATION_REQUIRED","Review exact package digest");
        if(!(b.get("allow") instanceof Boolean allow))throw new IllegalArgumentException("allow boolean required");
        var key=new VersionPluginPolicy.GrantKey(str(b.get("targetTenant")),version(b),str(b.get("capability")),str(b.get("operation")),str(b.get("dataScope")),TrustedContext.Kind.valueOf(str(b.get("principalKind"))),str(b.get("principalId")));
        Instant expiry=b.get("expiresAt")==null?null:Instant.parse(str(b.get("expiresAt")));
        return ok(application.changeGrant(identity.current(),key,allow,expiry,number(b.get("expectedRevision"))));
    }
    private static VersionPluginPolicy.Version version(Map<String,Object> b){return new VersionPluginPolicy.Version(str(b.get("pluginId")),str(b.get("version")),str(b.get("digest")));}
    private static Map<String,Object> body(String text,Set<String> fields){if(text.length()>1024*1024)throw new IllegalArgumentException("Request limit");var b=map(Json.parse(text));if(!b.keySet().equals(fields))throw new IllegalArgumentException("Missing or unknown request fields");return b;}
}
