package dev.fcp.generator;

import fcp.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Real file/ZIP/renderer/application checks. This does NOT claim native Spring/MySQL acceptance. */
public final class EducationGeneratorRegression {
    private static int checks;
    @FunctionalInterface interface Checked {void run()throws Exception;}
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void rejects(Checked action,String label)throws Exception {try{action.run();throw new AssertionError("Accepted "+label);}catch(IOException expected){checks++;}}
    static Map<String,Object> json(Path p)throws Exception{return EducationSqlRenderer.map(Json.parse(Files.readString(p)));}
    static List<Object> list(Object o)throws IOException{return EducationSqlRenderer.list(o);}
    static Map<String,Object> map(Object o)throws IOException{return EducationSqlRenderer.map(o);}
    static Map<String,byte[]> unzip(Map<String,byte[]> zips)throws Exception {
        Map<String,byte[]> files=new TreeMap<>();
        for(byte[] bytes:zips.values())try(var z=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry e;while((e=z.getNextEntry())!=null){check(!e.isDirectory(),"ZIP only files");check(files.put(e.getName(),z.readAllBytes())==null,"no module collision");}}
        return files;
    }
    static Path fixture(Path source)throws Exception {
        Path root=Files.createTempDirectory("education-generator-");
        for(String file:EducationGeneratorSources.SOURCE_SHA256.keySet()){Path target=root.resolve(file);Files.createDirectories(target.getParent());Files.copy(source.resolve(file),target);}
        return root;
    }
    static String target(String zipPath){return zipPath.startsWith("main/java/")?"integration/ruoyi/src/"+zipPath:"integration/plus-ui/src/"+zipPath.substring(4);}
    @SuppressWarnings("unchecked") static Map<String,Object> copy(Map<String,Object> value){return (Map<String,Object>)Json.parse(Json.write(value));}
    public static void main(String[] args)throws Exception {
        Path source=Path.of(args[0]).toAbsolutePath();Path root=fixture(source);
        var rendered=EducationSqlRenderer.render(root);var second=EducationSqlRenderer.render(root);
        check(rendered.zipBySource().keySet().equals(Set.of("education:p042:foundation","education:p042:roster")),"stable reviewed source IDs");
        check(rendered.outputDigests().size()==10,"exact ten payloads");check(rendered.workspaceInputDigests().size()==11,"all eleven sources guarded");
        check(rendered.inputDigests().size()==18,"sources plus seven classpath resources");
        check(rendered.outputDigests().equals(second.outputDigests()),"deterministic output digests");
        for(String key:rendered.zipBySource().keySet())check(Arrays.equals(rendered.zipBySource().get(key),second.zipBySource().get(key)),"deterministic ZIP bytes");
        for(String module:List.of("foundation","roster")) {
            var one=EducationSqlRenderer.render(root,List.of(module));
            check(one.outputDigests().size()==5,"module exact five outputs");
            check(Arrays.equals(one.zipBySource().get("education:p042:"+module),rendered.zipBySource().get("education:p042:"+module)),"split/combine same bytes");
        }
        rejects(()->EducationSqlRenderer.render(root,List.of()),"empty modules");rejects(()->EducationSqlRenderer.render(root,List.of("all")),"unknown module");rejects(()->EducationSqlRenderer.render(root,List.of("foundation","foundation")),"duplicate module");
        Map<String,byte[]> files=unzip(rendered.zipBySource());
        for(var entry:files.entrySet())check(EducationSqlRenderer.digest(entry.getValue()).equals(rendered.outputDigests().get(entry.getKey())),"every output digest");
        Map<String,Object> provenance=map(Json.parse(new String(files.get("vue/api/fcp/education/generated/foundation.provenance.json"),StandardCharsets.UTF_8)));
        check(map(provenance.get("outputDigests")).size()==4,"no self-referential manifest digest");
        check(provenance.get("inputDigests").equals(rendered.inputDigests()),"complete module input evidence");
        String foundation=new String(files.get("main/java/org/dromara/fcp/education/generated/FoundationGeneratedController.java"),StandardCharsets.UTF_8);
        String roster=new String(files.get("main/java/org/dromara/fcp/education/generated/RosterGeneratedController.java"),StandardCharsets.UTF_8);
        check(foundation.contains("extends FoundationEndpointSupport"),"preserve audited manual boundary");
        check(foundation.contains("EducationScalars.text(body.get(\"name\"),190)")&&foundation.contains("EducationScalars.text(body.get(\"name\"),128)"),"SQL string lengths generate command constraints");
        check(foundation.contains("Set.of(\"intentId\",\"expectedRowVersion\",\"name\")"),"rename explicit whitelist");
        check(!foundation.contains("DeleteMapping")&&!roster.contains("PostMapping")&&!roster.contains("person_id"),"no destructive or PII routes");
        Map<String,Object> model=json(source.resolve(EducationSqlRenderer.MODEL));
        check(EducationSqlRenderer.reconstructSql(model).equals(Files.readString(source.resolve(EducationSqlRenderer.SQL))),"DDL all columns constraints parity");
        String original=EducationSqlRenderer.reconstructSql(model);
        for(String field:List.of("type","nullable","default","description")) {
            Map<String,Object> changed=copy(model);Map<String,Object> c=map(list(map(list(changed.get("tables")).get(0)).get("columns")).get(0));
            c.put(field,switch(field){case "type"->"VARCHAR(99)";case "nullable"->true;case "default"->"'x'";default->"changed";});
            check(!original.equals(EducationSqlRenderer.reconstructSql(changed)),"DDL derives "+field);
        }
        for(String field:List.of("primary_key","unique","indexes","checks","foreign_keys")) {
            Map<String,Object> changed=copy(model);Map<String,Object> t=map(list(changed.get("tables")).get(0));
            t.put(field,switch(field){case "primary_key"->List.of("tenant_id","host_kind");case "unique","indexes"->List.of(List.of("tenant_id"));case "checks"->List.of("1 = 0");default->List.of(Map.of("columns",List.of("tenant_id"),"target","ed_core_tenant_binding","target_columns",List.of("tenant_id")));});
            check(!original.equals(EducationSqlRenderer.reconstructSql(changed)),"DDL derives "+field);
        }
        Map<String,Map<String,Object>> tables=new LinkedHashMap<>();for(Object raw:list(model.get("tables")))tables.put((String)map(raw).get("name"),map(raw));
        Map<String,Object> rules=json(source.resolve("core/src/main/resources/education-generator/rules.json"));Map<String,Object> module=map(list(rules.get("modules")).get(0));
        var schema=EducationSqlRenderer.deriveModule(module,tables);
        Map<String,Object> name=map(list(tables.get("ed_education_org_unit").get("columns")).stream().filter(v->{try{return map(v).get("name").equals("name");}catch(Exception e){throw new RuntimeException(e);}}).findFirst().orElseThrow());
        name.put("type","VARCHAR(77) COLLATE utf8mb4_0900_ai_ci");
        var variant=EducationSqlRenderer.deriveModule(module,tables);
        check(!Json.write(schema).equals(Json.write(variant)),"schema changes for reviewed model length variant");
        check(EducationSqlRenderer.controller(module,variant).contains("EducationScalars.text(body.get(\"name\"),77)"),"actual Java output changes for SQL length");
        Map<String,Object> ruleVariant=copy(module);map(list(ruleVariant.get("resources")).get(0)).put("list",List.of());
        check(!Json.write(variant).equals(Json.write(EducationSqlRenderer.deriveModule(ruleVariant,tables))),"reviewed field selection changes output schema");
        rejects(()->EducationSqlRenderer.deriveField(Map.of("name","personId","column","person_id"),"ed_education_enrollment",tables),"PII cannot become generated field");
        String schemaText=Json.write(rendered.modules());check(schemaText.contains("uint64_string")&&schemaText.contains("SCHOOL_TIMEZONE_CALENDAR_DATE")&&schemaText.contains("UTC_INSTANT"),"lossless scalar semantics");
        check(!schemaText.contains("person_id")&&!schemaText.contains("student_code")&&!schemaText.contains("subject_code"),"closed public field projection");
        for(String file:List.of(EducationSqlRenderer.SQL,EducationSqlRenderer.MODEL,EducationSqlRenderer.CATALOG,EducationSqlRenderer.SOURCE_LOCK)) {
            Path p=root.resolve(file);byte[] bytes=Files.readAllBytes(p);Files.writeString(p,"\n",StandardOpenOption.APPEND);rejects(()->EducationSqlRenderer.render(root),"source drift "+file);Files.write(p,bytes);
        }
        Path sql=root.resolve(EducationSqlRenderer.SQL);byte[] saved=Files.readAllBytes(sql);Files.delete(sql);Files.createSymbolicLink(sql,source.resolve(EducationSqlRenderer.SQL));rejects(()->EducationSqlRenderer.render(root),"source symlink");Files.delete(sql);Files.write(sql,saved);
        Files.write(sql,new byte[2*1024*1024+1]);rejects(()->EducationSqlRenderer.render(root),"oversized source");Files.write(sql,saved);
        // Real transactional safe application; no baseline is hand-seeded.
        var workspace=new GeneratedSourceWorkspace(root);var plan=workspace.plan("000000:1",rendered.zipBySource(),rendered.workspaceInputDigests());
        for(Object entry:list(plan.get("files")))check(map(entry).get("status").equals("create"),"fresh output create");
        var receipt=workspace.apply("000000:1",(String)plan.get("planId"));
        for(var entry:files.entrySet())check(Arrays.equals(entry.getValue(),Files.readAllBytes(root.resolve(target(entry.getKey())))),"real safe apply payload");
        check(receipt.equals(workspace.apply("000000:1",(String)plan.get("planId"))),"apply idempotence");
        Path manual=root.resolve("integration/plus-ui/src/views/fcp/education/custom-extension.txt");Files.createDirectories(manual.getParent());Files.writeString(manual,"keep me");
        Path generated=root.resolve("integration/plus-ui/src/views/fcp/education/generated/Workspace.vue");Files.writeString(generated,"\n<!-- hand edit -->\n",StandardOpenOption.APPEND);
        var preserved=workspace.plan("000000:1",rendered.zipBySource(),rendered.workspaceInputDigests());
        check(list(preserved.get("files")).stream().anyMatch(v->{try{return map(v).get("status").equals("preserve_manual");}catch(Exception e){throw new RuntimeException(e);}}),"manual change retained");
        workspace.apply("000000:1",(String)preserved.get("planId"));check(Files.readString(generated).contains("hand edit")&&Files.readString(manual).equals("keep me"),"manual source and extension survive");
        var stale=workspace.plan("000000:1",rendered.zipBySource(),rendered.workspaceInputDigests());Files.writeString(sql,"\n",StandardOpenOption.APPEND);
        rejects(()->workspace.apply("000000:1",(String)stale.get("planId")),"SQL drift invalidates live plan");Files.write(sql,saved);
        check(EducationGeneratorSources.RENDERER_SHA256.equals(EducationSqlRenderer.digest(Files.readAllBytes(source.resolve("core/src/main/java/dev/fcp/generator/EducationSqlRenderer.java")))),"renderer source pin current");
        System.out.println("RESULT "+checks+" education-generator component checks PASS; native Spring/MySQL/browser NOT_RUN");
    }
}
