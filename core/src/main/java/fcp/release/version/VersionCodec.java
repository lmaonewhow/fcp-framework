package fcp.release.version;

import fcp.Json;
import java.util.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Strict v1 wire codec shared by default storage and external adapters. */
public final class VersionCodec {
    private VersionCodec() {}
    public static void id(String value) {if(value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))throw new IllegalArgumentException("Invalid version identifier");}
    static void text(String value,int max) {if(value==null||value.isBlank()||value.length()>max||value.chars().anyMatch(c->c<32))throw new IllegalArgumentException("Invalid metadata text");}
    static Map<String,String> attributes(Map<String,String> value) {
        Objects.requireNonNull(value);if(value.size()>128)throw new IllegalArgumentException("Too many attributes");
        TreeMap<String,String> copy=new TreeMap<>();value.forEach((k,v)->{text(k,128);text(v,2048);copy.put(k,v);});return Collections.unmodifiableMap(copy);
    }
    public static Map<String,Object> artifact(SystemArtifact a) {
        return Map.of("id",a.id(),"kind",a.kind().name(),"component",a.component(),"version",a.version(),"sha256",a.sha256(),"sizeBytes",a.sizeBytes(),"location",a.location(),"compatibility",a.compatibility(),"metadata",a.metadata());
    }
    public static Map<String,Object> release(SystemRelease r) {return Map.of("id",r.id(),"version",r.version(),"components",r.components(),"compatibility",r.compatibility(),"metadata",r.metadata());}
    public static SystemArtifact artifact(Object raw) {
        Map<String,Object> m=object(raw,Set.of("id","kind","component","version","sha256","sizeBytes","location","compatibility","metadata"));
        Object n=m.get("sizeBytes");if(!(n instanceof Number))throw new IllegalArgumentException("Invalid size");
        long size=new BigDecimal(n.toString()).longValueExact();
        return new SystemArtifact(string(m,"id"),SystemArtifact.Kind.valueOf(string(m,"kind")),string(m,"component"),string(m,"version"),string(m,"sha256"),size,string(m,"location"),strings(m.get("compatibility")),strings(m.get("metadata")));
    }
    public static SystemRelease release(Object raw) {Map<String,Object> m=object(raw,Set.of("id","version","components","compatibility","metadata"));return new SystemRelease(string(m,"id"),string(m,"version"),strings(m.get("components")),strings(m.get("compatibility")),strings(m.get("metadata")));}
    public static Map<String,Object> object(Object raw,Set<String> keys) {
        if(!(raw instanceof Map<?,?> m)||!m.keySet().equals(keys))throw new IllegalArgumentException("Unexpected version document fields");
        Map<String,Object> out=new LinkedHashMap<>();m.forEach((k,v)->out.put((String)k,v));return out;
    }
    static String string(Map<String,Object> m,String key){if(!(m.get(key) instanceof String s))throw new IllegalArgumentException("Expected string");return s;}
    static Map<String,String> strings(Object raw){if(!(raw instanceof Map<?,?> m))throw new IllegalArgumentException("Expected attributes");Map<String,String> out=new TreeMap<>();m.forEach((k,v)->{if(!(k instanceof String)||!(v instanceof String))throw new IllegalArgumentException("Expected string attributes");out.put((String)k,(String)v);});return out;}
    public static String canonical(Object value) {
        if(value instanceof Map<?,?> m){TreeMap<String,Object> sorted=new TreeMap<>();m.forEach((k,v)->sorted.put((String)k,normalize(v)));return Json.write(sorted);}
        return Json.write(value);
    }
    private static Object normalize(Object v){if(v instanceof Map<?,?> m){TreeMap<String,Object> x=new TreeMap<>();m.forEach((k,w)->x.put((String)k,normalize(w)));return x;}if(v instanceof List<?> l)return l.stream().map(VersionCodec::normalize).toList();return v;}
    public static String sha256(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    public static String sha256(String text){return sha256(text.getBytes(StandardCharsets.UTF_8));}
}
