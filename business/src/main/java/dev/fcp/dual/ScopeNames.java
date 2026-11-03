package dev.fcp.dual;

import fcp.FcpException;
import fcp.Schema;

/** Host-only scope construction. Never lowercase caller IDs: that may merge distinct scopes. */
public final class ScopeNames {
    private ScopeNames() {}
    public static String tenant(String tenant) {
        if(tenant==null || !tenant.matches("[a-z0-9_-]{1,64}"))
            throw new FcpException("FORBIDDEN","Missing or noncanonical tenant ID; configure an explicit host mapping");
        return "tenant-"+tenant;
    }
    public static String single(String scope) {
        if(scope==null || scope.isBlank())throw new FcpException("FORBIDDEN","An explicit single-system scope is required");
        Schema.ident(scope);return scope;
    }
    public static String subject(String subject) {
        if(subject==null || subject.isBlank() || subject.length()>100 || !subject.equals(subject.strip()))
            throw new FcpException("FORBIDDEN","Authenticated business user ID is missing or invalid");
        return subject;
    }
}
