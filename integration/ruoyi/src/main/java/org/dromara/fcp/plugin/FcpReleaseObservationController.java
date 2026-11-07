package org.dromara.fcp.plugin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import dev.fcp.dual.HostResponse;
import fcp.*;
import fcp.release.control.MetricsGate;
import fcp.release.observation.DurableRolloutAutomation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.*;
import static fcp.Schema.*;

@RestController
@ConditionalOnProperty(name={"fcp.release.enabled","fcp.release.observation.enabled"},havingValue="true")
@RequestMapping(value="/fcp/releases/automation",produces=MediaType.APPLICATION_JSON_VALUE)
public final class FcpReleaseObservationController {
    private final NativePluginHost host;private final DurableRolloutAutomation automation;
    public FcpReleaseObservationController(NativePluginHost host,DurableRolloutAutomation automation){this.host=host;this.automation=automation;}
    @ModelAttribute public void nativeHeader(HttpServletRequest request){String h=request.getHeader("Authorization");if(h==null||h.isBlank())throw new FcpException("UNAUTHENTICATED","Explicit native authorization required");String token=h.startsWith("Bearer ")?h.substring(7):h;String authenticated=StpUtil.getTokenValue();if(authenticated==null||!java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),authenticated.getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new FcpException("UNAUTHENTICATED","Native header mismatch");}
    private ResponseEntity<String> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(HostResponse.ok(value));}
    @GetMapping("/state") @SaCheckPermission("fcp:release:view") public ResponseEntity<String> state()throws Exception{return ok(automation.snapshot(host.current()));}
    @PostMapping("/enable") @SaCheckPermission("fcp:release:manage") public ResponseEntity<String> enable(@RequestBody String text)throws Exception{
        var b=body(text,Set.of("expectedRevision","stages","minimumDwellMillis","minimumSamples","maximumAgeMillis","maximumErrorRate","maximumP95Millis"));
        var policy=new DurableRolloutAutomation.Policy(list(b.get("stages")).stream().map(v->Math.toIntExact(number(v))).toList(),Duration.ofMillis(number(b.get("minimumDwellMillis"))),new MetricsGate.Limits(number(b.get("minimumSamples")),Duration.ofMillis(number(b.get("maximumAgeMillis"))),((Number)b.get("maximumErrorRate")).doubleValue(),((Number)b.get("maximumP95Millis")).doubleValue()));
        return ok(automation.enable(host.current(),number(b.get("expectedRevision")),policy));
    }
    @PostMapping("/disable") @SaCheckPermission("fcp:release:manage") public ResponseEntity<String> disable(@RequestBody String text)throws Exception{var b=body(text,Set.of("expectedRevision"));return ok(automation.disable(host.current(),number(b.get("expectedRevision"))));}
    @PostMapping("/tick") @SaCheckPermission("fcp:release:manage") public ResponseEntity<String> tick(@RequestBody String text)throws Exception{body(text,Set.of());return ok(automation.tick(host.current()));}
    private static Map<String,Object> body(String text,Set<String> keys){if(text.length()>16384)throw new IllegalArgumentException("Observation request size");var b=map(Json.parse(text));if(!b.keySet().equals(keys))throw new IllegalArgumentException("Observation request schema");return b;}
}
