package dev.fcp.host;

import fcp.host.HostCapabilities;
import fcp.host.TrustedContext;

/** Persistent plugin grants. Implementations must use fresh, tenant-bound state. */
public interface PluginPolicy {
    record Permission(boolean granted, String userPermission, long revision) { }
    Permission permission(TrustedContext context, HostCapabilities.Grant grant);
    void audit(HostCapabilities.Audit event);
}
