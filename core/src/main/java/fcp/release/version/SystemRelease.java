package fcp.release.version;

import java.util.Map;

/** Immutable composition of independently versioned components. Compatibility is declarative, not proof. */
public record SystemRelease(String id, String version, Map<String,String> components,
                            Map<String,String> compatibility, Map<String,String> metadata) {
    public SystemRelease {
        VersionCodec.id(id); VersionCodec.text(version,128);
        components=VersionCodec.attributes(components);
        if(components.isEmpty()) throw new IllegalArgumentException("Empty release");
        components.forEach((role,artifact)->{VersionCodec.id(role);VersionCodec.id(artifact);});
        compatibility=VersionCodec.attributes(compatibility); metadata=VersionCodec.attributes(metadata);
    }
}
