package dev.fcp.generator;

import fcp.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Reviewed, deterministic LOCKED P042 profile, never an arbitrary SQL/template interpreter.
 * Java independently reconstructs all DDL (columns/type/nullability/default, PK, unique,
 * indexes, CHECK and FK) from the locked model and requires byte-for-byte SQL parity.
 * No SQL is executed; no database, scripts, subprocesses or network are used here.
 */
public final class EducationSqlRenderer {
    public static final String PROFILE_ID="education-p042-v1", VERSION="1";
    public static final String MODEL="design/education-platform/v4.2.1/database/model.json";
    public static final String SQL="design/education-platform/v4.2.1/database/sql/P042__fresh_isolated_schema.sql";
    public static final String CATALOG="contracts/education/catalog.json", SOURCE_LOCK="contracts/education/sources.lock.json";
    private static final int MAX_FILE=2*1024*1024;
    private static final String RESOURCE="education-generator/";
    private EducationSqlRenderer() {}

    public record Rendered(Map<String,byte[]> zipBySource, Map<String,String> inputDigests,
            Map<String,String> workspaceInputDigests, Map<String,String> outputDigests,
            Map<String,Object> provenance, List<Map<String,Object>> modules) {
        public Map<String,Object> profile() {
            return object("profileId",PROFILE_ID,"version",VERSION,"scope","LOCKED_P042_ONLY",
                "schemaVersion","P042","modules",modules,"inputDigests",inputDigests,"provenance",provenance,"templateSource","Running generator classpath; rebuild to activate edited source templates");
        }
    }
    public static Rendered render(Path root) throws IOException { return render(root,List.of("foundation","roster")); }
    public static Rendered render(Path root,List<String> selected) throws IOException {
        if(selected==null||selected.isEmpty()||selected.size()>2||new HashSet<>(selected).size()!=selected.size()
                ||!Set.of("foundation","roster").containsAll(selected))throw new IOException("Select reviewed foundation/roster modules only");
        root=root.toAbsolutePath().normalize();checkPath(root);
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Missing source workspace");
        Map<String,String> inputs=new TreeMap<>(),guards=new TreeMap<>();
        Map<String,byte[]> sourceBytes=new HashMap<>();
        for(var entry:EducationGeneratorSources.SOURCE_SHA256.entrySet()) {
            byte[] bytes=readSource(root,entry.getKey());
            requireDigest(entry.getKey(),bytes,entry.getValue());sourceBytes.put(entry.getKey(),bytes);
            inputs.put(entry.getKey(),entry.getValue());guards.put(entry.getKey(),entry.getValue());
        }
        Map<String,Object> model=map(parse(sourceBytes.get(MODEL))),catalog=map(parse(sourceBytes.get(CATALOG)));
        Map<String,Object> lock=map(parse(sourceBytes.get(SOURCE_LOCK)));
        if(!"P042".equals(model.get("schema_version"))||list(model.get("tables")).size()!=54)throw new IOException("Unexpected P042 model");
        byte[] reconstructed=reconstructSql(model).getBytes(StandardCharsets.UTF_8);
        if(!Arrays.equals(reconstructed,sourceBytes.get(SQL)))throw new IOException("P042 SQL structure differs from locked model");
        verifyCatalog(model,catalog,lock);
        Map<String,Object> rules=map(parse(resource("rules.json",inputs)));
        if(!PROFILE_ID.equals(rules.get("profileId"))||!VERSION.equals(rules.get("version")))throw new IOException("Unknown education rules version");
        Map<String,Map<String,Object>> tables=new LinkedHashMap<>();
        for(Object table:list(model.get("tables"))) {Map<String,Object> t=map(table);if(tables.put(string(t,"name"),t)!=null)throw new IOException("Duplicate table");}
        Map<String,byte[]> zips=new TreeMap<>();Map<String,String> outputs=new TreeMap<>();List<Map<String,Object>> schemas=new ArrayList<>();
        Map<String,Object> evidence=object("profileId",PROFILE_ID,"version",VERSION,"schemaVersion","P042",
            "sourceBaseline",lock.get("baseline_commit"),"sqlVerification","MODEL_DDL_BYTE_PARITY_ALL_54_TABLES",
            "inputDigests",inputs,"rendererSourceSha256",EducationGeneratorSources.RENDERER_SHA256,"personDisplayPolicy","PENDING",
            "deniedOperations",rules.get("deniedOperations"),"databaseWrites",false);
        // Load every template before producing anything, so all modules carry identical complete input evidence.
        Map<String,String> templates=new HashMap<>();
        for(String module:List.of("foundation","roster"))for(String file:List.of("Workspace.vue","schema.ts","api.ts")) {
            String path="templates/"+module+"/"+file+".tpl";
            templates.put(path,new String(resource(path,inputs),StandardCharsets.UTF_8));
        }
        for(Object raw:list(rules.get("modules"))) {
            Map<String,Object> module=map(raw);String id=string(module,"id");if(!selected.contains(id))continue;
            Map<String,Object> schema=deriveModule(module,tables);schemas.add(schema);
            Map<String,byte[]> files=new TreeMap<>();
            Map<String,String> tokens=Map.of("@@PROFILE_ID@@",PROFILE_ID,"@@MODULE_ID@@",id,
                "@@SCHEMA_DIGEST@@",inputs.get(SQL),"@@MODULE_SCHEMA_JSON@@",Json.write(schema),
                "@@API_ENDPOINTS_JSON@@",Json.write(module.get("clientEndpoints")));
            for(String file:List.of("Workspace.vue","schema.ts","api.ts")) {
                String source="templates/"+id+"/"+file+".tpl", text=templates.get(source);
                for(var token:tokens.entrySet())text=text.replace(token.getKey(),token.getValue());
                if(Pattern.compile("@@[A-Z_]+@@").matcher(text).find())throw new IOException("Unresolved template token: "+source);
                String target=file.equals("Workspace.vue")?"vue/views/fcp/"+(id.equals("foundation")?"education":"roster")+"/generated/Workspace.vue"
                    :"vue/api/fcp/education/generated/"+id+"."+file;
                files.put(target,text.getBytes(StandardCharsets.UTF_8));
            }
            String className=capitalize(id)+"GeneratedController";
            files.put("main/java/org/dromara/fcp/education/generated/"+className+".java",controller(module,schema).getBytes(StandardCharsets.UTF_8));
            Map<String,String> contentDigests=new TreeMap<>();files.forEach((path,bytes)->contentDigests.put(path,digest(bytes)));
            Map<String,Object> provenance=new LinkedHashMap<>(evidence);
            provenance.put("module",id);provenance.put("sourceId","education:p042:"+id);
            provenance.put("outputDigests",contentDigests);provenance.put("outputDigestScope","All module payloads except this self-referential provenance file");
            files.put("vue/api/fcp/education/generated/"+id+".provenance.json",(Json.write(provenance)+"\n").getBytes(StandardCharsets.UTF_8));
            files.forEach((path,bytes)->outputs.put(path,digest(bytes)));
            zips.put("education:p042:"+id,zip(files));
        }
        evidence.put("outputDigests",outputs);
        evidence.put("rendererClassSha256",classDigest());
        Map<String,String> targets=new TreeMap<>();for(String path:outputs.keySet())targets.put(path,path.startsWith("main/java/")?"integration/ruoyi/src/"+path:"integration/plus-ui/src/"+path.substring(4));
        evidence.put("outputTargets",targets);
        return new Rendered(Collections.unmodifiableMap(zips),Collections.unmodifiableMap(inputs),Collections.unmodifiableMap(guards),
            Collections.unmodifiableMap(outputs),Collections.unmodifiableMap(evidence),List.copyOf(schemas));
    }
    static Map<String,Object> deriveModule(Map<String,Object> module,Map<String,Map<String,Object>> tables)throws IOException {
        List<Map<String,Object>> resources=new ArrayList<>();
        for(Object raw:list(module.get("resources"))) {
            Map<String,Object> rule=map(raw);String table=string(rule,"table");
            Map<String,Object> result=object("id",rule.get("id"),"table",table,"label",rule.get("label"),"allowedOperations",rule.get("allowedOperations"));
            for(String mode:List.of("list","detail","create","rename")) {
                List<Map<String,Object>> fields=new ArrayList<>();
                for(Object field:list(rule.get(mode)))fields.add(deriveField(map(field),table,tables));
                result.put(mode,fields);
            }
            resources.add(result);
        }
        return object("module",module.get("id"),"profileId",PROFILE_ID,"schemaVersion","P042","sourceId","education:p042:"+module.get("id"),
            "resources",resources,"endpoints",module.get("clientEndpoints"),"personDisplayPolicy","PENDING");
    }
    /** Only called with hash-pinned field rules. Public for deterministic structural regression tests. */
    static Map<String,Object> deriveField(Map<String,Object> rule,String defaultTable,Map<String,Map<String,Object>> tables)throws IOException {
        String table=Objects.toString(rule.getOrDefault("table",defaultTable)),column=string(rule,"column");
        Map<String,Object> t=tables.get(table);if(t==null)throw new IOException("Unreviewed field table");
        Map<String,Object> found=null;for(Object raw:list(t.get("columns")))if(column.equals(map(raw).get("name")))found=map(raw);
        if(found==null||!"普通".equals(found.get("classification"))||Set.of("tenant_id","person_id","student_code","subject_code").contains(column))throw new IOException("Field not in reviewed non-PII projection: "+table+"."+column);
        String sqlType=string(found,"type"),scalar=sqlType.equals("BIGINT UNSIGNED")?"uint64_string":sqlType.equals("DATETIME(6)")?"utc_timestamp":sqlType.equals("DATE")?"business_date":sqlType.startsWith("VARCHAR(")?"text":null;
        if(scalar==null)throw new IOException("Unsupported reviewed scalar");
        Map<String,Object> field=new LinkedHashMap<>(rule);field.put("table",table);field.put("sqlType",sqlType);field.put("scalar",scalar);field.put("nullable",found.get("nullable"));
        Matcher length=Pattern.compile("VARCHAR\\((\\d+)\\).*").matcher(sqlType);if(length.matches())field.put("maxLength",Integer.parseInt(length.group(1)));
        if(scalar.equals("business_date"))field.put("timeSemantics","SCHOOL_TIMEZONE_CALENDAR_DATE");
        if(scalar.equals("utc_timestamp"))field.put("timeSemantics","UTC_INSTANT");
        return field;
    }
    private static void verifyCatalog(Map<String,Object> model,Map<String,Object> catalog,Map<String,Object> lock)throws IOException {
        if(!"P042".equals(catalog.get("schema"))||!Objects.equals(lock,catalog.get("source_lock")))throw new IOException("Catalog lock mismatch");
        List<Object> mt=list(model.get("tables")),ct=list(catalog.get("tables"));if(mt.size()!=ct.size())throw new IOException("Catalog table mismatch");
        for(int i=0;i<mt.size();i++) {
            Map<String,Object> m=map(mt.get(i)),c=map(ct.get(i));
            for(String key:List.of("name","primary_key","foreign_keys","unique","checks"))if(!Objects.equals(m.get(key),c.get(key)))throw new IOException("Catalog structure mismatch: "+key);
            List<Object> mc=list(m.get("columns")),cc=list(c.get("columns"));if(mc.size()!=cc.size())throw new IOException("Catalog column count");
            for(int j=0;j<mc.size();j++) {
                Map<String,Object> a=map(mc.get(j)),b=map(cc.get(j));
                for(String key:List.of("name","nullable","classification","description","reference","reference_enforcement"))if(!Objects.equals(a.get(key),b.get(key)))throw new IOException("Catalog column mismatch: "+key);
                if(!Objects.equals(a.get("type"),b.get("sql_type")))throw new IOException("Catalog type mismatch");
            }
        }
    }
    /** Exact independent Java port of the reviewed P042 DDL serializer; no execution. */
    public static String reconstructSql(Map<String,Object> model)throws IOException {
        List<String> out=new ArrayList<>(List.of("-- Education platform schema 4.2. FRESH ISOLATED DATABASE ONLY.",
            "-- This is not a migration for an existing FCP database. Runtime users must not have DDL rights.",
            "-- Native MySQL 8.4 acceptance is a separate, mandatory gate.","SET NAMES utf8mb4 COLLATE utf8mb4_0900_bin;","SET SESSION time_zone = '+00:00';",""));
        for(Object raw:list(model.get("tables"))) {
            Map<String,Object> t=map(raw);List<String> lines=new ArrayList<>();
            for(Object rawColumn:list(t.get("columns"))) {
                Map<String,Object> c=map(rawColumn);
                String line="  "+quote(string(c,"name"))+" "+string(c,"type")+(Boolean.TRUE.equals(c.get("nullable"))?" NULL":" NOT NULL");
                if(c.get("default")!=null)line+=" DEFAULT "+c.get("default");
                line+=" COMMENT '"+string(c,"description").replace("\\","\\\\").replace("'","''").replace("\n"," ")+"'";lines.add(line);
            }
            lines.add("  PRIMARY KEY ("+quoted(list(t.get("primary_key")))+")");
            int i=0;for(Object key:list(t.get("unique")))lines.add("  UNIQUE KEY "+quote("uq_"+(++i))+" ("+quoted(list(key))+")");
            i=0;for(Object key:list(t.get("indexes")))lines.add("  KEY "+quote("ix_"+(++i))+" ("+quoted(list(key))+")");
            i=0;for(Object check:list(t.get("checks")))lines.add("  CONSTRAINT "+quote("ck_"+string(t,"name").substring(3)+"_"+(++i))+" CHECK ("+check+")");
            out.add("CREATE TABLE "+quote(string(t,"name"))+" (");out.add(String.join(",\n",lines));out.add(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;");out.add("");
        }
        for(Object raw:list(model.get("tables"))) {
            Map<String,Object> t=map(raw);int i=0;
            for(Object rawFk:list(t.get("foreign_keys"))) {Map<String,Object> f=map(rawFk);out.add("ALTER TABLE "+quote(string(t,"name"))+" ADD CONSTRAINT "+quote("fk_"+string(t,"name").substring(3)+"_"+(++i))+" FOREIGN KEY ("+quoted(list(f.get("columns")))+") REFERENCES "+quote(string(f,"target"))+" ("+quoted(list(f.get("target_columns")))+") ON UPDATE RESTRICT ON DELETE RESTRICT;");}
        }
        return String.join("\n",out)+"\n";
    }
    private static String quote(String s)throws IOException{if(!s.matches("[a-z][a-z0-9_]*"))throw new IOException("Unsafe model identifier");return "`"+s+"`";}
    private static String quoted(List<Object> fields)throws IOException{List<String> q=new ArrayList<>();for(Object value:fields)q.add(quote((String)value));return String.join(", ",q);}
    private static byte[] resource(String name,Map<String,String> inputs)throws IOException {
        String expected=EducationGeneratorSources.RESOURCE_SHA256.get(name);if(expected==null)throw new IOException("Unreviewed classpath resource");
        try(InputStream input=EducationSqlRenderer.class.getClassLoader().getResourceAsStream(RESOURCE+name)) {
            if(input==null)throw new IOException("Education generator resources not packaged: "+name);
            byte[] bytes=input.readNBytes(MAX_FILE+1);if(bytes.length>MAX_FILE)throw new IOException("Template size limit");
            requireDigest(name,bytes,expected);inputs.put("classpath:"+RESOURCE+name,expected);return bytes;
        }
    }
    private static String classDigest()throws IOException {
        try(InputStream stream=EducationSqlRenderer.class.getResourceAsStream("EducationSqlRenderer.class")) {
            if(stream==null)throw new IOException("Renderer bytecode unavailable");return digest(stream.readNBytes(MAX_FILE));
        }
    }
    private static byte[] readSource(Path root,String name)throws IOException {
        if(name.startsWith("/")||name.contains("\\")||Arrays.stream(name.split("/")).anyMatch(p->p.equals("..")||p.equals(".")||p.isEmpty()))throw new IOException("Unsafe fixed source path");
        Path file=root.resolve(name);checkPath(file);
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_FILE)throw new IOException("Missing/oversized education source: "+name);
        try(InputStream stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=stream.readNBytes(MAX_FILE+1);if(bytes.length>MAX_FILE)throw new IOException("Source size limit");return bytes;}
    }
    private static void checkPath(Path file)throws IOException {for(Path p=file;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Education source symlink forbidden");}
    static String digest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static void requireDigest(String name,byte[] bytes,String expected)throws IOException {if(!digest(bytes).equals(expected))throw new IOException("Education source drift: "+name);}
    private static Object parse(byte[] bytes)throws IOException {try{return Json.parse(new String(bytes,StandardCharsets.UTF_8));}catch(RuntimeException error){throw new IOException("Invalid education JSON",error);}}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value)throws IOException {if(!(value instanceof Map))throw new IOException("Object required");return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") static List<Object> list(Object value)throws IOException {if(!(value instanceof List))throw new IOException("List required");return (List<Object>)value;}
    private static String string(Map<String,Object> map,String key)throws IOException {if(!(map.get(key) instanceof String s))throw new IOException("String required: "+key);return s;}
    static Map<String,Object> object(Object... pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private static String capitalize(String s){return Character.toUpperCase(s.charAt(0))+s.substring(1);}
    private static byte[] zip(Map<String,byte[]> files)throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(output,StandardCharsets.UTF_8)){for(var file:files.entrySet()){ZipEntry entry=new ZipEntry(file.getKey());entry.setTime(0);zip.putNextEntry(entry);zip.write(file.getValue());zip.closeEntry();}}
        return output.toByteArray();
    }
    static String controller(Map<String,Object> module,Map<String,Object> schema)throws IOException {
        String id=string(module,"id"),name=capitalize(id),className=name+"GeneratedController";
        StringBuilder out=new StringBuilder("// Generated by education-p042-v1 from verified P042 DDL and reviewed endpoint rules.\n")
            .append("// Edit hand-maintained EndpointSupport/domain services for custom behavior; regeneration is conflict protected.\n")
            .append("package org.dromara.fcp.education.generated;\n\n")
            .append("import fcp.Json;\nimport fcp.education.EducationScalars;\nimport fcp.education.").append(name).append("Store;\n")
            .append("import org.dromara.fcp.education.NativeFoundationIdentity;\nimport org.dromara.fcp.education.").append(name).append("EndpointSupport;\n")
            .append("import org.springframework.beans.factory.ObjectProvider;\nimport org.springframework.http.ResponseEntity;\n")
            .append("import org.springframework.util.MultiValueMap;\nimport org.springframework.web.bind.annotation.*;\nimport java.util.*;\n\n")
            .append("@RestController\n@RequestMapping(").append(Json.write(module.get("basePath"))).append(")\n")
            .append("public final class ").append(className).append(" extends ").append(name).append("EndpointSupport {\n")
            .append("    public ").append(className).append("(NativeFoundationIdentity identity,ObjectProvider<").append(name).append("Store> stores){super(identity,stores);}\n");
        for(Object raw:list(module.get("endpoints"))) {
            Map<String,Object> endpoint=map(raw);String method=string(endpoint,"name");
            out.append("    @Override\n    @").append(endpoint.get("method").equals("GET")?"GetMapping":"PostMapping")
                .append("(").append(Json.write(endpoint.get("path"))).append(")\n    public ResponseEntity<Map<String,Object>> ").append(method).append("(");
            List<String> parameters=new ArrayList<>(),args=new ArrayList<>(),checks=new ArrayList<>();
            for(Object p:list(endpoint.get("parameters"))) {
                Map<String,Object> param=map(p);String key=string(param,"name"),source=string(param,"source"),type=string(param,"type");
                parameters.add((source.equals("path")?"@PathVariable("+Json.write(key)+") ":source.equals("body")?"@RequestBody ":"@RequestParam ")
                    +(type.equals("Map")?"Map<String,String>":type.equals("MultiValueMap")?"MultiValueMap<String,String>":"String")+" "+key);args.add(key);
                if(source.equals("path")&&Set.of("schoolId","id").contains(key))checks.add("EducationScalars.unsignedBigint("+key+",true);");
                if(source.equals("path")&&key.equals("intentId"))checks.add("EducationScalars.text(intentId,100);");
                if(source.equals("query")) {
                    String allowed=String.join(",",list(endpoint.get("query")).stream().map(Json::write).toList());
                    checks.add("if(!Set.of("+allowed+").containsAll(parameters.keySet()))throw new IllegalArgumentException(\"Unexpected query parameters\");");
                    if(type.equals("Map")&&list(endpoint.get("query")).contains("afterId"))checks.add("if(parameters.containsKey(\"afterId\"))EducationScalars.unsignedBigint(parameters.get(\"afterId\"),false);");
                    if(type.equals("Map")&&list(endpoint.get("query")).contains("termId"))checks.add("if(parameters.containsKey(\"termId\"))EducationScalars.unsignedBigint(parameters.get(\"termId\"),true);");
                }
            }
            if(endpoint.get("command")!=null) {
                Map<String,Object> command=map(endpoint.get("command"));String resource=string(command,"resource");
                checks.add("guardCommand("+(resource.equals("$kind")?"kind":Json.write(resource))+","+Json.write(command.get("action"))+",body);");
            }
            out.append(String.join(",",parameters)).append("){\n");for(String check:checks)out.append("        ").append(check).append("\n");
            out.append("        return super.").append(method).append("(").append(String.join(",",args)).append(");\n    }\n");
        }
        if(id.equals("foundation")) {
            out.append("    @SuppressWarnings(\"unchecked\")\n    private static void guardCommand(String resource,String action,String text){\n")
                .append("        if(text==null||text.length()>16384)throw new IllegalArgumentException(\"Request too large\");\n")
                .append("        Object parsed=Json.parse(text);if(!(parsed instanceof Map<?,?>))throw new IllegalArgumentException(\"Object required\");\n")
                .append("        Map<String,Object> body=(Map<String,Object>)parsed;\n        switch(resource+\":\"+action){\n");
            for(Object raw:list(schema.get("resources"))) {
                Map<String,Object> resource=map(raw);
                for(String action:List.of("create","rename")) {
                    List<Object> fields=list(resource.get(action));if(fields.isEmpty())continue;
                    out.append("            case ").append(Json.write(resource.get("id")+":"+action)).append(" -> {\n")
                        .append("                if(!body.keySet().equals(Set.of(").append(String.join(",",fields.stream().map(v->{try{return Json.write(map(v).get("name"));}catch(IOException e){throw new IllegalStateException(e);}}).toList()))
                        .append(")))throw new IllegalArgumentException(\"Unexpected command fields\");\n");
                    for(Object value:fields) {
                        Map<String,Object> field=map(value);String key=Json.write(field.get("name")),scalar=string(field,"scalar");
                        String expression="body.get("+key+")",check=scalar.equals("uint64_string")?"EducationScalars.unsignedBigint("+expression+","+!"row_version".equals(field.get("column"))+")"
                            :scalar.equals("utc_timestamp")?"EducationScalars.utcTimestamp("+expression+")":"EducationScalars.text("+expression+","+field.get("maxLength")+")";
                        out.append("                ");if(Boolean.TRUE.equals(field.get("nullable")))out.append("if(").append(expression).append("!=null)");out.append(check).append(";\n");
                        if(field.get("options")!=null)out.append("                if(!Set.of(").append(String.join(",",list(field.get("options")).stream().map(Json::write).toList())).append(").contains(").append(expression).append("))throw new IllegalArgumentException(\"Unreviewed option\");\n");
                    }
                    out.append("            }\n");
                }
            }
            out.append("            default -> throw new IllegalArgumentException(\"Unreviewed education command\");\n        }\n    }\n");
        }
        return out.append("}\n").toString();
    }
}
