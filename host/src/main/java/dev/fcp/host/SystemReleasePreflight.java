package dev.fcp.host;

import fcp.release.control.*;
import fcp.release.version.*;
import java.io.IOException;
import java.util.*;

/** Verifies registered composition and independently observed schema, never client compatibility claims. */
public final class SystemReleasePreflight implements ReleaseController.Preflight {
    public record SchemaState(long version,boolean dirty) {}
    @FunctionalInterface public interface SchemaProbe { SchemaState current() throws IOException; }
    private final VersionProvider versions;
    private final SchemaProbe schema;
    public SystemReleasePreflight(VersionProvider versions,SchemaProbe schema){this.versions=Objects.requireNonNull(versions);this.schema=Objects.requireNonNull(schema);}
    @Override public void verify(ReleasePlan plan)throws IOException {
        verifyRelease(plan.stableRelease(),plan.stableDigest(),plan.stableSchemaMin(),plan.stableSchemaMax());
        verifyRelease(plan.candidateRelease(),plan.candidateDigest(),plan.candidateSchemaMin(),plan.candidateSchemaMax());
        SchemaState state=schema.current();
        try{plan.requireCompatibleSchema(state.version(),state.dirty());}catch(IllegalStateException e){throw new IOException("SCHEMA_INCOMPATIBLE_OR_DIRTY",e);}
    }
    private void verifyRelease(String id,String digest,long min,long max)throws IOException {
        SystemRelease release=versions.release(id).orElseThrow(()->new IOException("SYSTEM_VERSION_UNKNOWN"));
        if(!VersionCodec.sha256(VersionCodec.canonical(VersionCodec.release(release))).equals(digest))throw new IOException("SYSTEM_MANIFEST_DIGEST_MISMATCH");
        try {
            if(Long.parseLong(release.compatibility().get("schemaMin"))!=min || Long.parseLong(release.compatibility().get("schemaMax"))!=max)
                throw new IOException("SCHEMA_WINDOW_DIFFERS_FROM_REGISTERED_VERSION");
        } catch(NullPointerException|NumberFormatException failure){throw new IOException("SYSTEM_VERSION_SCHEMA_WINDOW_REQUIRED",failure);}
        for(String artifact:release.components().values())if(versions.artifact(artifact).isEmpty())throw new IOException("SYSTEM_ARTIFACT_UNKNOWN");
        // The configured executor MUST verify actual bytes/provenance against these immutable references before READY.
        // This method does not fetch artifact URLs and cannot certify that deployment has taken place.
    }
}
