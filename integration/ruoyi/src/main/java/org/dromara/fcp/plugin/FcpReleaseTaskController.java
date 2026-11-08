package org.dromara.fcp.plugin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import dev.fcp.dual.HostResponse;
import dev.fcp.host.ReleaseTaskApplication;
import fcp.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static fcp.Schema.*;

@RestController
@ConditionalOnProperty(name={"fcp.plugin.release.enabled","fcp.plugin.release.tasks.enabled"},havingValue="true")
@RequestMapping(value="/fcp/release-tasks",produces=MediaType.APPLICATION_JSON_VALUE)
@SaCheckPermission("fcp:release:manage")
public final class FcpReleaseTaskController {
    private final NativePluginHost identity;private final ReleaseTaskApplication application;
    public FcpReleaseTaskController(NativePluginHost identity,ReleaseTaskApplication application){this.identity=identity;this.application=application;}
    @ModelAttribute public void explicitToken(HttpServletRequest request){String h=request.getHeader("Authorization");if(h==null||h.isBlank())throw new FcpException("UNAUTHENTICATED","Native token header required");String token=h.startsWith("Bearer ")?h.substring(7):h;String authenticated=StpUtil.getTokenValue();if(authenticated==null||!java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),authenticated.getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new FcpException("UNAUTHENTICATED","Native authorization mismatch");}
    private ResponseEntity<String> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(HostResponse.ok(value));}
    @GetMapping("/{definition}/{run}") public ResponseEntity<String> status(@PathVariable String definition,@PathVariable String run)throws Exception{return ok(application.status(identity.current(),definition,run));}
    @PostMapping("/{definition}/{run}/pin") public ResponseEntity<String> pin(@PathVariable String definition,@PathVariable String run,@RequestBody String text)throws Exception{var b=body(text,Set.of("input"));return ok(application.pin(identity.current(),definition,run,map(b.get("input"))));}
    @PostMapping("/{definition}/{run}/run") public ResponseEntity<String> run(@PathVariable String definition,@PathVariable String run,@RequestBody String text)throws Exception{body(text,Set.of());return ok(application.run(identity.current(),definition,run));}
    private static Map<String,Object> body(String text,Set<String> fields){if(text.length()>70000)throw new IllegalArgumentException("Request limit");var b=map(Json.parse(text));if(!b.keySet().equals(fields))throw new IllegalArgumentException("Missing or unknown task fields");return b;}
}
