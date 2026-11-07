package fcp.host;
import java.util.function.Supplier;
/** Optional host-owned serialization fence for controlled host effects. Never supplied by plugin input. */
public interface HostCommitFence {
    Object commit(TrustedContext context,Supplier<Object> operation);
}
