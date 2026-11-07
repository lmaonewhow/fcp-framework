import fcp.*;
import fcp.host.*;
import fcp.storage.*;
import javax.sql.DataSource;
import java.sql.*;
import java.io.PrintWriter;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;

/** Actual MySQL test: dedicated disposable database only, V005 already applied by deployment identity.
 * No cleanup or credential output. DDL drift injection targets only newly generated test-owned tables. FCP_STORAGE_TEST_URL/USER/PASSWORD and DEPLOY_USER/PASSWORD.
 */
public final class MysqlStorageTest {
    static final class Source implements DataSource {
        final String user,password;Source(String u,String p){user=u;password=p;}
        public Connection getConnection()throws SQLException{return DriverManager.getConnection(System.getenv("FCP_STORAGE_TEST_URL"),user,password);}
        public Connection getConnection(String u,String p)throws SQLException{throw new UnsupportedOperationException();}
        public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter p){}public void setLoginTimeout(int s){}public int getLoginTimeout(){return 0;}public Logger getParentLogger(){return Logger.getGlobal();}public <T>T unwrap(Class<T>c)throws SQLException{throw new SQLException();}public boolean isWrapperFor(Class<?>c){return false;}
    }
    static final class Host implements HostCapabilities {
        boolean revoked;public Descriptor descriptor(){return new Descriptor("test","1","fcp.host/1","embedded-single-instance",Map.of());}public Clock clock(){return Clock.systemUTC();}
        public Decision authorize(TrustedContext c,Grant g){return new Decision(!revoked,1);}public void audit(Audit a){}
    }
    static void fails(String code,Runnable r){try{r.run();throw new AssertionError("Expected "+code);}catch(FcpException e){if(!code.equals(e.code))throw e;}}
    static TrustedContext ctx(String tenant,String principal){return new TrustedContext("request","trace",tenant,new TrustedContext.Principal(TrustedContext.Kind.USER,principal),1,Set.of(),Instant.now().plusSeconds(300));}
    public static void main(String[] args)throws Exception {
        if(System.getenv("FCP_STORAGE_TEST_URL")==null){System.out.println("BLOCKED: dedicated actual MySQL URL and credentials not supplied");System.exit(2);}
        var runtime=new Source(System.getenv("FCP_STORAGE_TEST_USER"),System.getenv("FCP_STORAGE_TEST_PASSWORD"));
        var deploy=new Source(System.getenv("FCP_STORAGE_TEST_DEPLOY_USER"),System.getenv("FCP_STORAGE_TEST_DEPLOY_PASSWORD"));
        var h=new Host();String plugin="test.p"+UUID.randomUUID().toString().replace("-","");
        var c=ctx("t1","u1");var migration=new AdditiveSchemaMigrator(deploy,h);
        var schema=new StorageSchema("students",1,Map.of("name",StorageSchema.Type.STRING),Set.of("name"));migration.apply(c,plugin,schema,0);migration.apply(c,plugin,schema,0);
        var store=new JdbcPluginStorage(runtime,h);var a=store.bind(plugin,c,"class:1");
        var r=a.put("students","a",Map.of("name","Alice"),0,"create");if(r.revision()!=1)throw new AssertionError();
        if(a.put("students","a",Map.of("name","Alice"),0,"create").revision()!=1)throw new AssertionError("replay");
        fails("IDEMPOTENCY_CONFLICT",()->a.put("students","a",Map.of("name","Bob"),0,"create"));
        fails("VERSION_CONFLICT",()->a.put("students","a",Map.of("name","Bob"),0,"other"));
        if(store.bind(plugin,ctx("t2","u1"),"class:1").get("students","a").isPresent())throw new AssertionError("tenant leak");
        if(store.bind(plugin,c,"class:2").get("students","a").isPresent())throw new AssertionError("class leak");
        if(!a.list("students",Map.of("name","Alice"),null,10).equals(List.of(r)))throw new AssertionError("query");
        a.delete("students","a",1,"delete");if(a.get("students","a").isPresent())throw new AssertionError("delete visible");
        a.put("students","b",Map.of("name","Alice"),0,"new");fails("UNIQUE_CONFLICT",()->a.restore("students","a",2,"restore"));
        a.delete("students","b",1,"delete-b");if(a.restore("students","a",2,"restore").deleted())throw new AssertionError("restore");
        migration.apply(c,plugin,new StorageSchema("students",2,Map.of("name",StorageSchema.Type.STRING,"age",StorageSchema.Type.NUMBER),Set.of("name")),1);
        fails("DESTRUCTIVE_MIGRATION_REJECTED",()->migration.apply(c,plugin,new StorageSchema("students",3,Map.of("name",StorageSchema.Type.NUMBER),Set.of("name")),2));
        var physical=new PhysicalSchemaMigrator(deploy,h);
        var ddl=PhysicalSchemaMigrator.plan(plugin,"roster",List.of(
            new PhysicalSchemaMigrator.Step(1,PhysicalSchemaMigrator.Kind.CREATE,null,null),
            new PhysicalSchemaMigrator.Step(2,PhysicalSchemaMigrator.Kind.ADD_NULLABLE_FIELD,"name",PhysicalSchemaMigrator.Type.TEXT),
            new PhysicalSchemaMigrator.Step(3,PhysicalSchemaMigrator.Kind.ADD_INDEX,"name",null)));
        physical.apply(c,ddl,ddl.checksum());physical.apply(c,ddl,ddl.checksum());
        var changed=PhysicalSchemaMigrator.plan(plugin,"roster",List.of(new PhysicalSchemaMigrator.Step(2,PhysicalSchemaMigrator.Kind.ADD_NULLABLE_FIELD,"name",PhysicalSchemaMigrator.Type.INTEGER)));
        fails("MIGRATION_CONFLICT",()->physical.apply(c,changed,changed.checksum()));
        String declaredPlugin=plugin+"d";
        var deployer=new StorageDeploymentService(deploy,h);
        var declaration1=fcp.storage.StorageDeclarationTest.declaration(1);
        var plan=deployer.plan(declaredPlugin,declaration1);deployer.apply(c,plan,plan.digest());deployer.apply(c,plan,plan.digest());
        var verifier=new JdbcStorageCompatibility(runtime);verifier.verify(declaredPlugin,"1.0.0","",declaration1);
        var physicalSession=store.bind(declaredPlugin,c,"class:1");
        physicalSession.put("roster","one",Map.of("name","A","score",12.25,"metadata",Map.of("tag","x")),0,"physical-create");
        if(physicalSession.get("roster","one").isEmpty())throw new AssertionError("physical read");
        if(!store.bind(declaredPlugin,ctx("t2","u1"),"class:1").list("roster",Map.of(),null,10).isEmpty())throw new AssertionError("physical tenant leak");
        if(!store.bind(declaredPlugin,c,"class:2").list("roster",Map.of(),null,10).isEmpty())throw new AssertionError("physical scope leak");
        fails("UNIQUE_CONFLICT",()->physicalSession.put("roster","two",Map.of("name","A"),0,"physical-duplicate"));
        physicalSession.delete("roster","one",1,"physical-delete");physicalSession.put("roster","two",Map.of("name","A"),0,"physical-second");
        fails("UNIQUE_CONFLICT",()->physicalSession.restore("roster","one",2,"physical-restore"));
        physicalSession.delete("roster","two",1,"physical-delete-second");physicalSession.restore("roster","one",2,"physical-restore");
        var declaration2=fcp.storage.StorageDeclarationTest.declaration(2);var upgrade=deployer.plan(declaredPlugin,declaration2);deployer.apply(c,upgrade,upgrade.digest());
        verifier.verify(declaredPlugin,"2.0.0","",declaration2);verifier.verify(declaredPlugin,"1.0.0","",declaration1);
        physicalSession.put("roster","one",Map.of("nickname","Ada"),3,"physical-upgrade");
        if(!"A".equals(physicalSession.get("roster","one").orElseThrow().body().get("name")))throw new AssertionError("Partial update lost existing field");
        var incompatible=new HashMap<>(declaration1);incompatible.put("compatibleVersions",List.of(1));fails("STORAGE_VERSION_INCOMPATIBLE",()->verifier.verify(declaredPlugin,"1.0.0","",incompatible));
        // Deliberate metadata drift fault injection in this newly generated dedicated test table.
        // These ALTERs are TEST-ONLY deployment-identity operations, never accepted by plugin APIs.
        String physicalTable=PhysicalSchemaMigrator.plan(declaredPlugin,"roster",List.of(new PhysicalSchemaMigrator.Step(1,PhysicalSchemaMigrator.Kind.CREATE,null,null))).table();
        String uniqueIndex="idx_"+JdbcPluginStorage.hash("unique_name").substring(0,16);
        try(Connection fault=deploy.getConnection();Statement ddlFault=fault.createStatement()){
            ddlFault.setQueryTimeout(30);
            ddlFault.execute("ALTER TABLE `"+physicalTable+"` MODIFY COLUMN recorded_at DATETIME(0) NULL");
            fails("PHYSICAL_SCHEMA_DRIFT",()->verifier.verify(declaredPlugin,"2.0.0","",declaration2));
            ddlFault.execute("ALTER TABLE `"+physicalTable+"` MODIFY COLUMN recorded_at DATETIME(6) NULL");
            ddlFault.execute("ALTER TABLE `"+physicalTable+"` DROP INDEX `"+uniqueIndex+"`, ADD INDEX `"+uniqueIndex+"` (`name`(16))");
            fails("PHYSICAL_SCHEMA_DRIFT",()->verifier.verify(declaredPlugin,"2.0.0","",declaration2));
            ddlFault.execute("ALTER TABLE `"+physicalTable+"` DROP INDEX `"+uniqueIndex+"`, ADD INDEX `"+uniqueIndex+"` (`name`)");
            ddlFault.execute("ALTER TABLE `"+physicalTable+"` DROP PRIMARY KEY, ADD PRIMARY KEY(tenant_id(16),data_scope,record_id)");
            fails("PHYSICAL_SCHEMA_DRIFT",()->verifier.verify(declaredPlugin,"2.0.0","",declaration2));
            ddlFault.execute("ALTER TABLE `"+physicalTable+"` DROP PRIMARY KEY, ADD PRIMARY KEY(tenant_id,data_scope,record_id)");
            ddlFault.execute("ALTER TABLE `"+physicalTable+"` MODIFY COLUMN `name` VARCHAR(512) COLLATE utf8mb4_general_ci NULL");
            fails("PHYSICAL_SCHEMA_DRIFT",()->verifier.verify(declaredPlugin,"2.0.0","",declaration2));
            ddlFault.execute("ALTER TABLE `"+physicalTable+"` MODIFY COLUMN `name` VARCHAR(512) COLLATE utf8mb4_bin NULL");
            ddlFault.execute("ALTER TABLE `"+physicalTable+"` MODIFY COLUMN tenant_id VARCHAR(128) COLLATE utf8mb4_general_ci NOT NULL");
            fails("PHYSICAL_SCHEMA_DRIFT",()->verifier.verify(declaredPlugin,"2.0.0","",declaration2));
            ddlFault.execute("ALTER TABLE `"+physicalTable+"` MODIFY COLUMN tenant_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL");
        }
        verifier.verify(declaredPlugin,"2.0.0","",declaration2);
        h.revoked=true;fails("PERMISSION_DENIED",()->a.put("students","a",Map.of("name","Alice"),0,"create"));h.revoked=false;
        try(var journal=new JdbcStateJournal(runtime,"j"+UUID.randomUUID().toString().replace("-",""))){if(journal.load().revision()!=0)throw new AssertionError();journal.compareAndSet(0,"{}");try{journal.compareAndSet(0,"{}");throw new AssertionError("CAS accepted");}catch(java.io.IOException expected){if(!expected.getMessage().equals("JOURNAL_CONFLICT"))throw expected;}if(journal.load().revision()!=1)throw new AssertionError();}
        System.out.println("PASS actual MySQL storage, additive logical migration, lifecycle journal");
    }
}
