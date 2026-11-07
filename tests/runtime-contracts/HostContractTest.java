import fcp.host.HostCapabilities;
import fcp.host.TrustedContext;
import fcp.FcpException;
import java.time.*;
import java.util.*;

public class HostContractTest {
    public static void main(String[] args) {
        var now = Instant.parse("2026-01-01T00:00:00Z");
        var scopes = new HashSet<>(Set.of("class-a"));
        var context = new TrustedContext("r1", "trace1", "tenant-a",
            new TrustedContext.Principal(TrustedContext.Kind.USER, "u1"), 1, scopes, now.plusSeconds(60));
        scopes.clear();
        if (!context.dataScopes().equals(Set.of("class-a"))) throw new AssertionError("Context mutated");
        class Host implements HostCapabilities {
            boolean permitted = true;
            public Descriptor descriptor() { return new Descriptor("test", "1", "fcp.host/1", "embedded-single-instance", Map.of()); }
            public Clock clock() { return Clock.fixed(now, ZoneOffset.UTC); }
            public Decision authorize(TrustedContext c, Grant grant) { return new Decision(permitted, permitted ? 1 : 2); }
            public void audit(Audit event) { }
        }
        var host = new Host();
        var grant = new HostCapabilities.Grant("demo", "storage", "read", "class-a");
        host.require(context, grant);
        host.permitted = false;
        try { host.require(context, grant); throw new AssertionError("Revocation bypass"); }
        catch (FcpException expected) { if (!expected.code.equals("PERMISSION_DENIED")) throw expected; }
        var expired = new TrustedContext("r2", "trace2", "tenant-a", context.principal(), 1, Set.of(), now);
        try { host.require(expired, grant); throw new AssertionError("Expired context accepted"); }
        catch (FcpException expected) { if (!expected.code.equals("DEADLINE_EXCEEDED")) throw expected; }
        System.out.println("Host contract tests PASS (unit adapter, not RuoYi integration)");
    }
}
