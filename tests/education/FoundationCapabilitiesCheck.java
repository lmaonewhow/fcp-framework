import fcp.education.FoundationCapabilities;
import java.util.Map;
public final class FoundationCapabilitiesCheck {
    public static void main(String[] args) {
        Map<String,Object> data=FoundationCapabilities.blocked();
        if (!"BLOCKED".equals(data.get("status"))) throw new AssertionError();
        Map<?,?> operations=(Map<?,?>)data.get("operations");
        if (operations.size()!=11 || operations.values().stream().anyMatch(v -> !Boolean.FALSE.equals(v))) throw new AssertionError();
        if (!"education:foundation:view".equals(FoundationCapabilities.VIEW_PERMISSION)) throw new AssertionError();
        try {operations.clear();throw new AssertionError();} catch(UnsupportedOperationException expected) {}
        System.out.println("PASS: immutable fail-closed capability snapshot only; native host NOT_RUN");
    }
}
