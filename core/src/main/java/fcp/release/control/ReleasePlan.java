package fcp.release.control;

import java.util.*;
import fcp.Schema;

/** A verified system release composition, not a source-control branch. */
public record ReleasePlan(String environment, String stableRelease, String candidateRelease,
                          String stableDigest, String candidateDigest, long schemaVersion,
                          long stableSchemaMin, long stableSchemaMax, long candidateSchemaMin,
                          long candidateSchemaMax) {
    public ReleasePlan {
        for (String id : List.of(environment, stableRelease, candidateRelease))
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) throw new IllegalArgumentException("Invalid release identifier");
        for (String digest : List.of(stableDigest,candidateDigest))
            if (!digest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid manifest digest");
        if (stableRelease.equals(candidateRelease) || schemaVersion < 0 || stableSchemaMin < 0 || candidateSchemaMin < 0 ||
            stableSchemaMax < stableSchemaMin || candidateSchemaMax < candidateSchemaMin) throw new IllegalArgumentException("Invalid release plan");
    }
    public void requireCompatibleSchema(long actual, boolean dirty) {
        if (dirty || actual != schemaVersion || actual < stableSchemaMin || actual > stableSchemaMax ||
                actual < candidateSchemaMin || actual > candidateSchemaMax) throw new IllegalStateException("SCHEMA_INCOMPATIBLE_OR_DIRTY");
    }
    public Map<String,Object> json() { return Map.of("environment",environment,"stableRelease",stableRelease,
            "candidateRelease",candidateRelease,"stableDigest",stableDigest,"candidateDigest",candidateDigest,
            "schemaVersion",schemaVersion,"stableSchemaMin",stableSchemaMin,"stableSchemaMax",stableSchemaMax,
            "candidateSchemaMin",candidateSchemaMin,"candidateSchemaMax",candidateSchemaMax); }
    public static ReleasePlan parse(Map<String,Object> m) {
        if (!m.keySet().equals(Set.of("environment","stableRelease","candidateRelease","stableDigest","candidateDigest","schemaVersion","stableSchemaMin","stableSchemaMax","candidateSchemaMin","candidateSchemaMax"))) throw new IllegalArgumentException("Release plan schema");
        return new ReleasePlan(Schema.str(m.get("environment")),Schema.str(m.get("stableRelease")),Schema.str(m.get("candidateRelease")),Schema.str(m.get("stableDigest")),Schema.str(m.get("candidateDigest")),Schema.number(m.get("schemaVersion")),Schema.number(m.get("stableSchemaMin")),Schema.number(m.get("stableSchemaMax")),Schema.number(m.get("candidateSchemaMin")),Schema.number(m.get("candidateSchemaMax")));
    }
}
