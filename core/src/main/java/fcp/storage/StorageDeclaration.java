package fcp.storage;

import fcp.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** Strict manifest-storage projection supported by the MySQL adapter. */
public record StorageDeclaration(long version,Set<Long> compatibleVersions,Map<String,Table> tables,Map<String,Object> source) {
    public record Field(String name,PhysicalSchemaMigrator.Type type,boolean nullable,int maxLength) {
        public Field { physicalName(name);Objects.requireNonNull(type);if(maxLength<1||maxLength>512)throw new IllegalArgumentException("Physical string length must be 1..512"); }
        public Object normalize(Object v){
            if(v==null){if(!nullable)throw JdbcPluginStorage.error("REQUIRED_FIELD");return null;}
            try{return switch(type){
                case TEXT -> {if(!(v instanceof String s)||s.codePointCount(0,s.length())>maxLength)throw new IllegalArgumentException();yield s;}
                case INTEGER -> {if(!(v instanceof Number n))throw new IllegalArgumentException();long x=new BigDecimal(n.toString()).longValueExact();if(Math.abs((double)x)>9007199254740991d)throw new IllegalArgumentException();yield x;}
                case DECIMAL -> {if(!(v instanceof Number n))throw new IllegalArgumentException();BigDecimal d=new BigDecimal(n.toString()).setScale(4,java.math.RoundingMode.UNNECESSARY);if(d.precision()>20)throw new IllegalArgumentException();yield d;}
                case BOOLEAN -> {if(!(v instanceof Boolean))throw new IllegalArgumentException();yield v;}
                case TIMESTAMP -> {if(!(v instanceof String s))throw new IllegalArgumentException();LocalDateTime time=LocalDateTime.parse(s);if(time.getYear()<1000||time.getYear()>9999||time.getNano()%1000!=0)throw new IllegalArgumentException();yield time.toString();}
                case JSON -> {Json.write(v);yield Json.copy(v);}
            };}catch(RuntimeException e){throw JdbcPluginStorage.error("INVALID_FIELD");}
        }
    }
    public record Index(String name,List<String> fields,boolean unique){public Index{physicalName(name);fields=List.copyOf(fields);if(fields.isEmpty()||fields.size()>4||new HashSet<>(fields).size()!=fields.size())throw new IllegalArgumentException("Index arity");fields.forEach(StorageDeclaration::physicalName);}}
    public record Table(String name,Map<String,Field> fields,List<Index> indexes,Map<String,Object> source){
        public Table{StorageSchema.identifier(name);fields=Collections.unmodifiableMap(new TreeMap<>(fields));indexes=List.copyOf(indexes);source=Map.copyOf(source);if(fields.isEmpty()||fields.size()>64)throw new IllegalArgumentException("Field count");for(Index i:indexes){if(!fields.keySet().containsAll(i.fields()))throw new IllegalArgumentException("Unknown index field");int bytes=0;for(String f:i.fields()){if(fields.get(f).type()==PhysicalSchemaMigrator.Type.JSON)throw new IllegalArgumentException("JSON index unsupported");bytes+=fields.get(f).type()==PhysicalSchemaMigrator.Type.TEXT?2048:16;}if(bytes>3072)throw new IllegalArgumentException("Index exceeds MySQL byte bound");}}
        public Map<String,Object> normalize(Map<String,Object> input,boolean partial){if(!fields.keySet().containsAll(input.keySet()))throw JdbcPluginStorage.error("UNDECLARED_FIELD");Map<String,Object> out=new TreeMap<>();for(Field f:fields.values())if(input.containsKey(f.name()))out.put(f.name(),f.normalize(input.get(f.name())));else if(!partial)out.put(f.name(),f.normalize(null));if(Json.write(out).length()>65536)throw new IllegalArgumentException("Record limit");return out;}
    }
    public StorageDeclaration{compatibleVersions=Set.copyOf(compatibleVersions);tables=Collections.unmodifiableMap(new TreeMap<>(tables));source=Collections.unmodifiableMap(new TreeMap<>(source));}
    public String json(){return canonical(source);}
    public String digest(){return JdbcPluginStorage.hash(json());}
    @SuppressWarnings("unchecked") public static StorageDeclaration parse(Map<String,Object> input){
        Map<String,Object> s=(Map<String,Object>)Json.parse(Json.write(input));keys(s,Set.of("schemaVersion","compatibleVersions","tables","migrations"));long version=integer(s.get("schemaVersion"));if(version<1||version>1000000)throw new IllegalArgumentException("Schema version");
        Set<Long> compatible=new TreeSet<>();for(Object v:list(s.getOrDefault("compatibleVersions",List.of()))){long n=integer(v);if(n<1||n>1000000)throw new IllegalArgumentException("Compatible version");compatible.add(n);}
        Map<String,Table> tables=new TreeMap<>();for(Object value:list(s.getOrDefault("tables",List.of()))){var t=object(value);Table table=parseTable(t);if(tables.put(table.name(),table)!=null)throw new IllegalArgumentException("Duplicate table");}
        if(tables.isEmpty()||tables.size()>64)throw new IllegalArgumentException("Table count");
        for(Object value:list(s.getOrDefault("migrations",List.of()))){var migration=object(value);keys(migration,Set.of("from","to","operations"));long from=integer(migration.get("from"));if(from<0||integer(migration.get("to"))!=from+1)throw new IllegalArgumentException("Nonadditive migration");for(Object o:list(migration.get("operations"))){var op=object(o);keys(op,Set.of("type","table","field"));if(!"addField".equals(op.get("type")))throw new IllegalArgumentException("Unsupported migration");Table table=tables.get(op.get("table"));Field f=table==null?null:table.fields().get(op.get("field"));if(f==null||!f.nullable())throw new IllegalArgumentException("Added field must be nullable");}}
        return new StorageDeclaration(version,compatible,tables,s);
    }
    public static Table parseTable(Map<String,Object> t){keys(t,Set.of("name","fields","indexes"));String name=string(t.get("name"));Map<String,Field> fields=new TreeMap<>();for(Object v:list(t.get("fields"))){var f=object(v);keys(f,Set.of("name","type","nullable","maxLength"));PhysicalSchemaMigrator.Type type=switch(string(f.get("type"))){case "string"->PhysicalSchemaMigrator.Type.TEXT;case "integer"->PhysicalSchemaMigrator.Type.INTEGER;case "decimal"->PhysicalSchemaMigrator.Type.DECIMAL;case "boolean"->PhysicalSchemaMigrator.Type.BOOLEAN;case "timestamp"->PhysicalSchemaMigrator.Type.TIMESTAMP;case "json"->PhysicalSchemaMigrator.Type.JSON;default->throw new IllegalArgumentException("Unsupported type");};Field field=new Field(string(f.get("name")),type,bool(f.get("nullable")),Math.toIntExact(integer(f.getOrDefault("maxLength",512))));if(fields.put(field.name(),field)!=null)throw new IllegalArgumentException("Duplicate field");}
        List<Index> indexes=new ArrayList<>();Set<String> names=new HashSet<>();for(Object v:list(t.getOrDefault("indexes",List.of()))){var i=object(v);keys(i,Set.of("name","fields","unique"));Index index=new Index(string(i.get("name")),list(i.get("fields")).stream().map(StorageDeclaration::string).toList(),bool(i.get("unique")));if(!names.add(index.name()))throw new IllegalArgumentException("Duplicate index");indexes.add(index);}return new Table(name,fields,indexes,t);
    }
    static void physicalName(String n){if(n==null||!n.matches("[a-z][a-z0-9_]{0,47}")||Set.of("tenant_id","data_scope","record_id","revision","del").contains(n))throw new IllegalArgumentException("Invalid physical identifier");}
    static String canonical(Object value){if(value instanceof Map<?,?> m){Map<String,Object> sorted=new TreeMap<>();m.forEach((k,v)->sorted.put((String)k,Json.parse(canonical(v))));return Json.write(sorted);}if(value instanceof List<?> l)return Json.write(l.stream().map(v->Json.parse(canonical(v))).toList());return Json.write(value);}
    static void keys(Map<String,Object> m,Set<String> allowed){if(!allowed.containsAll(m.keySet()))throw new IllegalArgumentException("Unknown declaration field");}
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object o){if(!(o instanceof Map))throw new IllegalArgumentException("Object required");return (Map<String,Object>)o;}
    static List<?> list(Object o){if(!(o instanceof List<?> l))throw new IllegalArgumentException("Array required");return l;}
    static String string(Object o){if(!(o instanceof String s))throw new IllegalArgumentException("String required");return s;}
    static boolean bool(Object o){if(!(o instanceof Boolean b))throw new IllegalArgumentException("Boolean required");return b;}
    static long integer(Object o){if(!(o instanceof Number n))throw new IllegalArgumentException("Integer required");return new BigDecimal(n.toString()).longValueExact();}
}
