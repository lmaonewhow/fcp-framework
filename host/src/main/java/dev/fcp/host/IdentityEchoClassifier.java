package dev.fcp.host;

import fcp.*;
import fcp.host.TrustedContext;
import fcp.release.observation.RequestObserver.BusinessOutcome;
import fcp.release.version.*;
import java.util.*;

/** Opt-in host semantic adapter for identity-echo/1 only. Not a general business success oracle. */
public final class IdentityEchoClassifier implements PluginReleaseApplication.BusinessClassifier {
    public record Binding(String plugin,String capability,String sha256,String contract){
        public Binding{if(plugin==null||capability==null||!plugin.matches("[A-Za-z0-9_.-]{1,128}")||!capability.matches("[A-Za-z0-9_.-]{1,128}")||sha256==null||!sha256.matches("[a-f0-9]{64}")||!"identity-echo/1".equals(contract))throw new IllegalArgumentException("Identity echo binding");}
    }
    private final VersionProvider versions;private final Set<Binding> bindings;
    public IdentityEchoClassifier(VersionProvider versions,Collection<Binding> bindings){this.versions=Objects.requireNonNull(versions);this.bindings=Set.copyOf(bindings);if(bindings.isEmpty()||bindings.size()>128)throw new IllegalArgumentException("Identity echo binding count");}
    public BusinessOutcome classify(TrustedContext c,String release,String plugin,String capability,Object result){return BusinessOutcome.UNKNOWN;}
    public BusinessOutcome classifyRequest(TrustedContext c,String release,String plugin,String capability,Map<String,Object> input,Object result){
        SystemArtifact selected=null;
        try{var system=versions.release(release).orElse(null);if(system==null)return BusinessOutcome.UNKNOWN;
            for(String id:system.components().values()){var artifact=versions.artifact(id).orElse(null);if(artifact!=null&&artifact.kind()==SystemArtifact.Kind.PLUGIN&&artifact.component().equals(plugin)){if(selected!=null)return BusinessOutcome.UNKNOWN;selected=artifact;}}
        }catch(RuntimeException unavailable){return BusinessOutcome.UNKNOWN;}
        if(selected==null||!bindings.contains(new Binding(plugin,capability,selected.sha256(),"identity-echo/1")))return BusinessOutcome.UNKNOWN;
        if(!input.keySet().stream().allMatch(Set.of("message","dependency")::contains)||!(input.get("message") instanceof String)||input.containsKey("dependency")&&!Boolean.FALSE.equals(input.get("dependency")))return BusinessOutcome.UNKNOWN;
        if(!(result instanceof Map<?,?> value))return BusinessOutcome.UNKNOWN;
        if(!value.keySet().equals(Set.of("language","packageVersion","message","tenantId","principalKind","principalId","pluginPid")))return BusinessOutcome.UNKNOWN;
        if(!(value.get("tenantId") instanceof String)||!(value.get("principalId") instanceof String)||!(value.get("principalKind") instanceof String))return BusinessOutcome.UNKNOWN;
        if(!c.tenantId().equals(value.get("tenantId"))||!c.principal().id().equals(value.get("principalId"))||!c.principal().kind().name().equals(value.get("principalKind")))throw new PluginReleaseApplication.ResultIdentityViolation();
        if(!selected.version().equals(value.get("packageVersion"))||!input.get("message").equals(value.get("message")))return BusinessOutcome.FAILURE;
        if(!"python".equals(value.get("language"))||!(value.get("pluginPid") instanceof Number pid)||pid.longValue()<1||!Double.isFinite(pid.doubleValue())||pid.doubleValue()!=pid.longValue())return BusinessOutcome.UNKNOWN;
        return BusinessOutcome.SUCCESS;
    }
}
