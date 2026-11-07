package org.dromara.fcp.release;
import dev.fcp.host.ui.*;
import fcp.FcpException;
import org.dromara.fcp.plugin.NativePluginHost;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
@RestController
@RequestMapping("/fcp/ui")
public class NativeUiReleaseController {
    private final org.springframework.beans.factory.ObjectProvider<UiReleaseApplication> application;private final org.springframework.beans.factory.ObjectProvider<UiBundleStore> store;private final org.springframework.beans.factory.ObjectProvider<NativePluginHost> identity;
    public NativeUiReleaseController(org.springframework.beans.factory.ObjectProvider<UiReleaseApplication> application,org.springframework.beans.factory.ObjectProvider<UiBundleStore> store,org.springframework.beans.factory.ObjectProvider<NativePluginHost> identity){this.application=application;this.store=store;this.identity=identity;}
    @GetMapping("/releases/bootstrap") public ResponseEntity<Map<String,Object>> bootstrap(@RequestParam String shell,@RequestHeader(value="X-FCP-Release-Affinity",required=false)String affinity,HttpServletRequest request)throws Exception {
        if(application.getIfAvailable()==null)return ResponseEntity.notFound().header("Cache-Control","no-store").build();
        String header=request.getHeader("Authorization");if(header==null||header.isBlank())throw new FcpException("UNAUTHENTICATED","Explicit native authorization required");
        String token=header.startsWith("Bearer ")?header.substring(7):header,authenticated=cn.dev33.satoken.stp.StpUtil.getTokenValue();
        if(authenticated==null||!java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),authenticated.getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new FcpException("UNAUTHENTICATED","Native header mismatch");
        return ResponseEntity.ok().header("Cache-Control","private, no-store").header("Vary","Authorization, clientid, X-FCP-Release-Affinity").body(Map.of("code",200,"msg","success","data",application.getObject().bootstrap(identity.getObject().current(),shell,affinity)));
    }
    /** Static trusted assets contain no account/tenant data. Their visibility is not a business authorization grant. */
    @GetMapping("/assets/{digest}/{shell}/**") public ResponseEntity<byte[]> asset(@PathVariable String digest,@PathVariable String shell,HttpServletRequest request)throws Exception {
        if(store.getIfAvailable()==null)return ResponseEntity.notFound().header("Cache-Control","no-store").build();
        String prefix="/fcp/ui/assets/"+digest+"/"+shell+"/",uri=request.getRequestURI();
        if(!uri.startsWith(prefix)||uri.contains("%"))return ResponseEntity.badRequest().build();
        String path=uri.substring(prefix.length());var bundle=store.getObject().bundle(digest,shell);var asset=path.equals("index.html")?store.getObject().index(bundle):store.getObject().asset(bundle,path);
        return ResponseEntity.ok().header("Cache-Control","public, max-age=31536000, immutable").header("X-Content-Type-Options","nosniff").eTag('"'+asset.digest()+'"').contentType(MediaType.parseMediaType(asset.contentType())).body(asset.bytes());
    }
}
