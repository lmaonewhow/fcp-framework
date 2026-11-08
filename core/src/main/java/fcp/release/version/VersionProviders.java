package fcp.release.version;

import java.util.*;

/** Explicit registration by the host; no package-controlled class loading or automatic URL registration. */
public final class VersionProviders {
    private final Map<String,VersionProvider> providers = new LinkedHashMap<>();
    public synchronized void register(VersionProvider provider) {
        Objects.requireNonNull(provider);VersionCodec.id(provider.id());
        if(!provider.capabilities().contains(VersionProvider.Capability.READ)) throw new IllegalArgumentException("Provider must support reads");
        if(providers.putIfAbsent(provider.id(),provider)!=null) throw new IllegalArgumentException("Duplicate provider id");
    }
    public synchronized VersionProvider require(String id) {
        VersionProvider value=providers.get(id);
        if(value==null) throw new IllegalArgumentException("Unknown version provider");
        return value;
    }
    public synchronized List<String> ids() {return List.copyOf(providers.keySet());}
}
