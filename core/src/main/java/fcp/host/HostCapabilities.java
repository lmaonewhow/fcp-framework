package fcp.host;

import fcp.FcpException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Framework-independent ports implemented by authenticated host adapters.
 * These are interfaces, not a claim that a second production host is supported.
 */
public interface HostCapabilities {
    record Descriptor(String hostId, String implementationVersion, String contractVersion,
                      String topology, Map<String, String> capabilities) {
        public Descriptor {
            Objects.requireNonNull(hostId); Objects.requireNonNull(implementationVersion);
            if (!"fcp.host/1".equals(contractVersion)) throw new IllegalArgumentException("Unsupported host contract");
            if (!Set.of("embedded-single-instance", "remote").contains(topology))
                throw new IllegalArgumentException("Unknown topology");
            capabilities = Map.copyOf(capabilities);
        }
    }
    /** target plugin is selected by the server routing table, not input JSON. */
    record Grant(String pluginId, String capability, String operation, String dataScope) {
        public Grant {
            Objects.requireNonNull(pluginId); Objects.requireNonNull(capability);
            Objects.requireNonNull(operation); Objects.requireNonNull(dataScope);
        }
    }
    record Decision(boolean allowed, long currentRevision) { }
    record Audit(String requestId, String traceId, String tenantId, String principalId,
                 String pluginId, String action, String outcome, Instant occurredAt) { }
    Descriptor descriptor();
    Clock clock();
    /** Rechecks current user/task permissions, plugin grants, tenant and data scope.
     * A cached permission decision must never survive revocation unnoticed.
     */
    Decision authorize(TrustedContext context, Grant grant);
    void audit(Audit event);

    default void require(TrustedContext context, Grant grant) {
        Objects.requireNonNull(context); Objects.requireNonNull(grant);
        if (!clock().instant().isBefore(context.deadline()))
            throw new FcpException("DEADLINE_EXCEEDED", "Request deadline expired");
        Decision result = authorize(context, grant);
        if (result == null || !result.allowed())
            throw new FcpException("PERMISSION_DENIED", "Capability access denied");
        // A changed revision does not itself imply denial: authorize must use live policy.
    }
}
