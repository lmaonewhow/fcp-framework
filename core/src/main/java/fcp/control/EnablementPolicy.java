package fcp.control;

import fcp.FcpException;
import fcp.host.TrustedContext;
import fcp.plugin.PluginManifest;
import java.util.Map;

/** Host-owned, read-only platform approval gate. Declarations and administrator privileges do not
 * create grants. Implementations inspect current tenant/plugin grants, declared capabilities and
 * approved data scopes, and return a stable fingerprint including relevant authorization revisions.
 * Caller business permissions must not be confused with platform approval of the plugin itself.
 */
@FunctionalInterface
public interface EnablementPolicy {
    String verify(TrustedContext context,PluginManifest manifest,String packageDigest,
                  Map<String,PluginManifest> dependencyClosure);
    static EnablementPolicy failClosed(){return (context,manifest,digest,closure)->{
        if(!manifest.requires().isEmpty())throw new FcpException("CAPABILITY_NOT_GRANTED","Explicit platform approval is required for plugin capabilities: "+manifest.pluginId());
        return "no-required-capabilities";
    };}
}
