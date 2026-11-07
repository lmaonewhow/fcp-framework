package fcp.storage;

import fcp.*;
import java.util.*;

/** Strict declaration/planning unit tests, no MySQL simulation. */
public final class StorageDeclarationTest {
    public static Map<String,Object> declaration(int version){
        List<Object> fields=new ArrayList<>(List.of(Map.of("name","name","type","string","nullable",false,"maxLength",100),Map.of("name","score","type","decimal","nullable",true),Map.of("name","metadata","type","json","nullable",true),Map.of("name","active","type","boolean","nullable",true),Map.of("name","recorded_at","type","timestamp","nullable",true)));
        List<Object> migrations=new ArrayList<>();if(version>1){fields.add(Map.of("name","nickname","type","string","nullable",true,"maxLength",100));migrations.add(Map.of("from",1,"to",2,"operations",List.of(Map.of("type","addField","table","roster","field","nickname"))));}
        return Map.of("schemaVersion",version,"compatibleVersions",List.of(1,2),"tables",List.of(Map.of("name","roster","fields",fields,"indexes",List.of(Map.of("name","unique_name","fields",List.of("name"),"unique",true)))),"migrations",migrations);
    }
    static void reject(Runnable action){try{action.run();throw new AssertionError("Accepted invalid declaration");}catch(IllegalArgumentException|FcpException expected){}}
    public static void main(String[] args){
        var old=StorageDeclaration.parse(declaration(1));var next=StorageDeclaration.parse(declaration(2));StorageDeploymentService.validateAdditive(old,next);
        var table=old.tables().get("roster");
        if(!table.normalize(Map.of("name","Alice","active",true),false).containsKey("metadata"))throw new AssertionError("Nullable field absent");
        reject(()->table.normalize(Map.of("active",true),false));reject(()->table.normalize(Map.of("name","x".repeat(101)),false));reject(()->table.normalize(Map.of("name","x","score",1.23456),false));reject(()->table.normalize(Map.of("name","x","tenant_id","evil"),false));
        var unknown=new HashMap<>(declaration(1));unknown.put("sql","DROP TABLE sys_user");reject(()->StorageDeclaration.parse(unknown));
        var missingMigration=new HashMap<>(declaration(2));missingMigration.put("migrations",List.of());reject(()->StorageDeploymentService.validateAdditive(old,StorageDeclaration.parse(missingMigration)));
        var physicalPlan=PhysicalSchemaMigrator.plan("p.test","roster",List.of(new PhysicalSchemaMigrator.Step(1,PhysicalSchemaMigrator.Kind.CREATE,null,null),new PhysicalSchemaMigrator.Step(2,PhysicalSchemaMigrator.Kind.ADD_NULLABLE_FIELD,"metadata",PhysicalSchemaMigrator.Type.JSON),new PhysicalSchemaMigrator.Step(3,PhysicalSchemaMigrator.Kind.ADD_INDEX,"score_pair",null,List.of("score","active"))));
        if(!PhysicalSchemaMigrator.sql(physicalPlan.table(),physicalPlan.steps().get(2)).contains("(`score`,`active`)"))throw new AssertionError("Composite index generation");
        reject(()->new PhysicalSchemaMigrator.Step(4,PhysicalSchemaMigrator.Kind.ADD_INDEX,"bad",null,List.of("tenant_id")));
        System.out.println("PASS StorageDeclarationTest (pure declaration/DDL-generation unit; MySQL NOT_RUN)");
    }
}
