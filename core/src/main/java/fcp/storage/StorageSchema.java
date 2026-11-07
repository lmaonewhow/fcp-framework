package fcp.storage;

import fcp.Json;
import java.util.*;

/** Declarative logical schema on the shared JSON record store. No SQL or physical names. */
public record StorageSchema(String collection, long version, Map<String,Type> fields, Set<String> uniqueFields) {
    public enum Type { STRING, NUMBER, BOOLEAN }
    public StorageSchema {
        identifier(collection); if(version<1 || fields.isEmpty() || fields.size()>64)throw new IllegalArgumentException("Invalid schema");
        fields=Map.copyOf(fields);uniqueFields=Set.copyOf(uniqueFields);
        fields.keySet().forEach(StorageSchema::identifier);
        if(!fields.keySet().containsAll(uniqueFields))throw new IllegalArgumentException("Unknown unique field");
    }
    /** Manifest plugin identity, not a SQL identifier. Physical table names remain bounded hashes. */
    public static String pluginIdentifier(String s){if(s==null||s.length()>128||!s.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*"))throw new IllegalArgumentException("Invalid plugin identifier");return s;}
    public static String identifier(String s){if(s==null||!s.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}"))throw new IllegalArgumentException("Invalid identifier");return s;}
    public void validate(Map<String,Object> body){
        if(!fields.keySet().containsAll(body.keySet()))throw new IllegalArgumentException("Undeclared field");
        body.forEach((k,v)->{if(v!=null && !switch(fields.get(k)){case STRING -> v instanceof String;case NUMBER -> v instanceof Number;case BOOLEAN -> v instanceof Boolean;})throw new IllegalArgumentException("Invalid field type");});
        if(Json.write(body).length()>65536)throw new IllegalArgumentException("Record too large");
    }
    public String json(){Map<String,Object> f=new TreeMap<>();fields.forEach((k,v)->f.put(k,v.name()));return Json.write(Map.of("collection",collection,"version",version,"fields",f,"uniqueFields",new TreeSet<>(uniqueFields)));}
    @SuppressWarnings("unchecked") public static StorageSchema parse(String json){var m=(Map<String,Object>)Json.parse(json);Map<String,Type> f=new TreeMap<>();((Map<String,Object>)m.get("fields")).forEach((k,v)->f.put(k,Type.valueOf((String)v)));return new StorageSchema((String)m.get("collection"),((Number)m.get("version")).longValue(),f,new HashSet<>((List<String>)m.get("uniqueFields")));}
}
