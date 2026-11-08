package fcp.release.version;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Host-configured provider. Authorization belongs at the calling application boundary. */
public interface VersionProvider {
    enum Capability { READ, REGISTER_IMMUTABLE }
    String id();
    Set<Capability> capabilities();
    SystemArtifact registerArtifact(SystemArtifact artifact);
    Optional<SystemArtifact> artifact(String id);
    List<SystemArtifact> artifacts();
    SystemRelease registerRelease(SystemRelease release);
    Optional<SystemRelease> release(String id);
    List<SystemRelease> releases();
}
