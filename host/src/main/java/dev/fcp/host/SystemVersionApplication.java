package dev.fcp.host;

import fcp.FcpException;
import fcp.host.*;
import fcp.release.version.*;
import java.util.*;

/** Authenticated system-version registry. Registration is not deployment or verification. */
public final class SystemVersionApplication {
    private final HostCapabilities host;
    private final VersionProvider versions;
    public SystemVersionApplication(HostCapabilities host,VersionProvider versions) {
        this.host=Objects.requireNonNull(host);this.versions=Objects.requireNonNull(versions);
    }
    private void require(TrustedContext context,String operation) {
        if(context.principal().kind()!=TrustedContext.Kind.USER || !host.clock().instant().isBefore(context.deadline()))
            throw new FcpException("PERMISSION_DENIED","Live user identity required");
        host.require(context,new HostCapabilities.Grant("control","system.release",operation,"tenant:"+context.tenantId()));
    }
    public Map<String,Object> list(TrustedContext context) {
        require(context,"view");
        return Map.of("provider",Map.of("id",versions.id(),"capabilities",versions.capabilities().stream().map(Enum::name).sorted().toList()),
            "artifacts",versions.artifacts().stream().map(VersionCodec::artifact).toList(),
            "releases",versions.releases().stream().map(VersionCodec::release).toList());
    }
    public Map<String,Object> registerArtifact(TrustedContext context,Map<String,Object> document) {
        require(context,"manage");audit(context,"register.artifact","INTENT");var result=VersionCodec.artifact(versions.registerArtifact(VersionCodec.artifact(document)));audit(context,"register.artifact","REGISTERED");return result;
    }
    private void audit(TrustedContext c,String action,String outcome){host.audit(new HostCapabilities.Audit(c.requestId(),c.traceId(),c.tenantId(),c.principal().id(),"control",action,outcome,host.clock().instant()));}
    public Map<String,Object> registerRelease(TrustedContext context,Map<String,Object> document) {
        require(context,"manage");audit(context,"register.version","INTENT");var result=VersionCodec.release(versions.registerRelease(VersionCodec.release(document)));audit(context,"register.version","REGISTERED");return result;
    }
}
