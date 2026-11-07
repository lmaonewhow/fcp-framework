package fcp.host;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Host-authenticated identity. Never deserialize this type from a plugin or HTTP body. */
public record TrustedContext(String requestId, String traceId, String tenantId,
                             Principal principal, long authorizationRevision,
                             Set<String> dataScopes, Instant deadline) {
    public enum Kind { USER, TASK }
    public record Principal(Kind kind, String id) {
        public Principal { Objects.requireNonNull(kind); id = required(id, "principal id"); }
    }
    public TrustedContext {
        requestId = required(requestId, "requestId");
        traceId = required(traceId, "traceId");
        tenantId = required(tenantId, "tenantId");
        Objects.requireNonNull(principal);
        if (authorizationRevision < 0) throw new IllegalArgumentException("Negative authorization revision");
        dataScopes = Set.copyOf(dataScopes);
        Objects.requireNonNull(deadline);
    }
    private static String required(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid " + name);
        return value;
    }
}
