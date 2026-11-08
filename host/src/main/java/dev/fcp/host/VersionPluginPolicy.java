package dev.fcp.host;

import fcp.host.*;
import fcp.plugin.PluginManifest;
import java.time.Instant;
import java.util.*;

/** Package-digest grants, independent of the legacy global installed-version pointer. */
public interface VersionPluginPolicy {
    record Version(String pluginId,String version,String digest) {
        public Version { text(pluginId,128);text(version,128);if(digest==null||!digest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid package digest"); }
    }
    record Identity(boolean active,Set<String> permissions,Set<String> dataScopes,long revision) {
        public Identity {permissions=Set.copyOf(permissions);dataScopes=Set.copyOf(dataScopes);if(revision<0)throw new IllegalArgumentException("Negative revision");}
    }
    record GrantKey(String tenant,Version version,String capability,String operation,String dataScope,TrustedContext.Kind principalKind,String principalId) {
        public GrantKey {text(tenant,128);Objects.requireNonNull(version);text(capability,128);text(operation,64);text(dataScope,256);Objects.requireNonNull(principalKind);text(principalId,256);
            if(principalKind==TrustedContext.Kind.USER&&!principalId.equals("*"))throw new IllegalArgumentException("USER approvals are tenant scoped");
            if(principalKind==TrustedContext.Kind.TASK&&principalId.equals("*"))throw new IllegalArgumentException("TASK approvals require exact principal");
        }
    }
    record Approval(GrantKey key,String permission,long revision,boolean allowed,Instant expiresAt) {}
    @FunctionalInterface interface LiveIdentity { Identity current(TrustedContext context); }
    @FunctionalInterface interface ManifestResolver { PluginManifest resolve(Version version); }
    @FunctionalInterface interface ManagementAuthorization { void require(TrustedContext actor,String targetTenant); }
    HostCapabilities.Decision authorize(TrustedContext context,Version version,HostCapabilities.Grant grant);
    Approval read(TrustedContext actor,GrantKey key);
    /** Permission is derived from verified manifest; caller cannot assign it. expectedRevision=0 creates. */
    Approval change(TrustedContext actor,GrantKey key,boolean allow,Instant expiresAt,long expectedRevision);
    private static void text(String value,int max){if(value==null||value.isBlank()||value.length()>max||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid grant field");}
}
