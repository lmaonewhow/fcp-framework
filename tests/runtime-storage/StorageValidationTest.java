import fcp.*;
import fcp.host.*;
import fcp.storage.*;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;

/** Pure boundary/unit test; not a database or MySQL acceptance test. */
public final class StorageValidationTest {
    static void rejects(Runnable r){try{r.run();throw new AssertionError("accepted");}catch(IllegalArgumentException|FcpException expected){}}
    public static void main(String[] args){
        StorageSchema s=new StorageSchema("students",1,Map.of("name",StorageSchema.Type.STRING,"age",StorageSchema.Type.NUMBER),Set.of("name"));
        if(!s.equals(StorageSchema.parse(s.json())))throw new AssertionError("schema roundtrip");
        rejects(()->s.validate(Map.of("sql","DROP TABLE")));rejects(()->s.validate(Map.of("age","bad")));
        rejects(()->new StorageSchema("students;drop",1,s.fields(),Set.of()));
        DataSource noDatabase=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{throw new AssertionError("Database touched before authorization");});
        HostCapabilities deny=new HostCapabilities(){public Descriptor descriptor(){return new Descriptor("test","1","fcp.host/1","embedded-single-instance",Map.of());}public Clock clock(){return Clock.systemUTC();}public Decision authorize(TrustedContext c,Grant g){return new Decision(false,2);}public void audit(Audit a){}};
        var ctx=new TrustedContext("req","trace","t1",new TrustedContext.Principal(TrustedContext.Kind.USER,"u1"),1,Set.of("class:1"),Instant.now().plusSeconds(60));
        var store=new JdbcPluginStorage(noDatabase,deny);
        rejects(()->store.bind("student.plugin",ctx,"tenant:t2"));rejects(()->store.bind("student.plugin",ctx,"host:*"));
        for(int length:List.of(65,128)){
            String plugin="p".repeat(length);
            if(!StorageSchema.pluginIdentifier(plugin).equals(plugin))throw new AssertionError("Plugin boundary rejected");
            store.bind(plugin,ctx,"class:1");new JdbcPhysicalStorage(noDatabase,deny).bind(plugin,ctx,"class:1");
            var longPlan=PhysicalSchemaMigrator.plan(plugin,"roster",List.of(new PhysicalSchemaMigrator.Step(1,PhysicalSchemaMigrator.Kind.CREATE,null,null)));
            if(longPlan.table().length()>64||!longPlan.table().matches("fcp_p_[a-f0-9]{16}_[a-f0-9]{16}"))throw new AssertionError("Unbounded physical name");
        }
        String tooLong="p".repeat(129);
        rejects(()->StorageSchema.pluginIdentifier(tooLong));
        rejects(()->store.bind(tooLong,ctx,"class:1"));
        rejects(()->new JdbcPhysicalStorage(noDatabase,deny).bind(tooLong,ctx,"class:1"));
        rejects(()->PhysicalSchemaMigrator.plan(tooLong,"roster",List.of(new PhysicalSchemaMigrator.Step(1,PhysicalSchemaMigrator.Kind.CREATE,null,null))));
        rejects(()->StorageSchema.identifier("c".repeat(65)));
        rejects(()->new StorageSchema("students",1,Map.of("f".repeat(65),StorageSchema.Type.STRING),Set.of()));
        rejects(()->StorageSchema.pluginIdentifier("p;drop"));
        var bound=store.bind("student.plugin",ctx,"class:1");
        rejects(()->bound.get("students","a"));rejects(()->bound.put("students","a",Map.of("name","a"),0,"key"));rejects(()->bound.restore("students","a",1,"restore"));
        var capabilities=new StorageCapabilities(store);
        rejects(()->capabilities.invoke("student.plugin",ctx,"get",Map.of("collection","students","id","a","dataScope","class:1","tenantId","t2")));
        for(var contract:StorageCapabilities.contracts()){
            Schema.check(contract.inputSchema());
            rejects(()->Schema.validate(Map.of("collection","students","dataScope","class:1","sql","SELECT 1"),contract.inputSchema()));
        }
        rejects(()->new PhysicalSchemaMigrator.Step(1,PhysicalSchemaMigrator.Kind.ADD_NULLABLE_FIELD,"del",PhysicalSchemaMigrator.Type.TEXT));
        rejects(()->new PhysicalSchemaMigrator.Step(1,PhysicalSchemaMigrator.Kind.ADD_NULLABLE_FIELD,"name;DROP TABLE sys_user",PhysicalSchemaMigrator.Type.TEXT));
        var plan=PhysicalSchemaMigrator.plan("student.plugin","roster",List.of(new PhysicalSchemaMigrator.Step(1,PhysicalSchemaMigrator.Kind.CREATE,null,null),new PhysicalSchemaMigrator.Step(2,PhysicalSchemaMigrator.Kind.ADD_NULLABLE_FIELD,"name",PhysicalSchemaMigrator.Type.TEXT)));
        if(!plan.table().matches("fcp_p_[a-f0-9]{16}_[a-f0-9]{16}"))throw new AssertionError("unsafe physical name");
        System.out.println("PASS StorageValidationTest (pure boundary unit; no database)");
    }
}
