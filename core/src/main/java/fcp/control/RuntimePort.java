package fcp.control;

import java.util.Set;

/** Adapter to the existing Engine/catalog and supervised runner, never a second execution engine.
 * Every publication is an atomic tenant catalog replacement. All methods are idempotent.
 * Invocation and task capability checks MUST recheck live host authorization, including while draining.
 */
public interface RuntimePort {
    /** Fail closed before loading any persisted intent. Must reject all new plugin admissions. */
    void resetAdmissions();
    /** Re-resolve from the trusted installer inventory and verify bytes; never trust the public record alone. */
    void verifyInstalledPackage(fcp.plugin.PackageInstaller.InstalledPackage pkg);
    void verifyStorageCompatibility(String plugin, String version, String digest);
    void prepare(String plugin, String version, String digest);
    /** Must verify runner identity and readiness, not merely existence of a PID. */
    boolean ready(String plugin, String version, String digest);
    void publish(String tenant, Set<String> plugins, long generation);
    long activeCalls(String tenant, String plugin);
    /** Reports cancellation requests only, never claims committed external work was rolled back. */
    void cancel(String tenant, String plugin);
    void stop(String plugin);
}
