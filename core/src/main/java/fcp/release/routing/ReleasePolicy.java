package fcp.release.routing;

import java.util.Set;

/** A single application's tenant rollout. Epoch changes explicitly invalidate outstanding affinities. */
public record ReleasePolicy(String id, String stableRelease, String candidateRelease, int basisPoints,
                            Set<String> allowTenants, Set<String> excludeTenants, boolean enabled, long epoch) {
    public ReleasePolicy {
        id = valid(id); stableRelease = valid(stableRelease); candidateRelease = valid(candidateRelease);
        if (basisPoints < 0 || basisPoints > 10000 || epoch < 0) throw new IllegalArgumentException("Invalid rollout bounds");
        allowTenants = Set.copyOf(allowTenants); excludeTenants = Set.copyOf(excludeTenants);
        allowTenants.forEach(ReleasePolicy::valid); excludeTenants.forEach(ReleasePolicy::valid);
    }
    static String valid(String s) {
        if (s == null || s.isBlank() || s.length() > 256 || s.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid release identity");
        return s;
    }
}
