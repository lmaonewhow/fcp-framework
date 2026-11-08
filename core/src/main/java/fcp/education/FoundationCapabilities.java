package fcp.education;

import java.util.List;
import java.util.Map;

/** Honest capability discovery, not a school grant, schema installation, or CRUD implementation. */
public final class FoundationCapabilities {
    private FoundationCapabilities() {}
    public static final String VIEW_PERMISSION = "education:foundation:view";
    public static Map<String, Object> blocked() {
        return Map.of("schemaVersion", "education-foundation-v1", "status", "BLOCKED",
            "readiness", Map.of("schoolAuthorization", "NOT_IMPLEMENTED",
                "transactionProtocol", "NOT_IMPLEMENTED", "nativeAcceptance", "NOT_RUN"),
            "operations", Map.ofEntries(Map.entry("orgList",false),Map.entry("orgDetail",false),
                Map.entry("orgCreate",false),Map.entry("orgRename",false),Map.entry("classList",false),
                Map.entry("classDetail",false),Map.entry("classCreate",false),Map.entry("classRename",false),
                Map.entry("termList",false),Map.entry("personRename",false),Map.entry("schoolCreate",false)),
            "blockers", List.of("SCHOOL_AUTHORIZATION_NOT_IMPLEMENTED",
                "REVOCATION_PROTOCOL_NOT_IMPLEMENTED", "TRANSACTION_PROTOCOL_NOT_IMPLEMENTED",
                "NATIVE_ACCEPTANCE_NOT_RUN"));
    }
}
