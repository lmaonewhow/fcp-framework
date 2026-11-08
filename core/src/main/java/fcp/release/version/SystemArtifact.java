package fcp.release.version;

import java.util.Map;

/** Immutable system artifact metadata, not a source-control branch. Location is descriptive, never executed. */
public record SystemArtifact(String id, Kind kind, String component, String version, String sha256,
                             long sizeBytes, String location, Map<String,String> compatibility,
                             Map<String,String> metadata) {
    public enum Kind { HOST_BACKEND, ADMIN_UI, TEACHER_UI, PLUGIN, SERVICE, CONFIG, SCHEMA }
    public SystemArtifact {
        VersionCodec.id(id); VersionCodec.id(component); VersionCodec.text(version, 128);
        if(kind==null || sha256==null || !sha256.matches("[a-f0-9]{64}") || sizeBytes<0 || sizeBytes>9007199254740991L)
            throw new IllegalArgumentException("Invalid artifact identity");
        VersionCodec.text(location, 2048);
        compatibility=VersionCodec.attributes(compatibility); metadata=VersionCodec.attributes(metadata);
    }
}
