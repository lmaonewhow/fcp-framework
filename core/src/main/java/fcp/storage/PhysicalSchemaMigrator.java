package fcp.storage;

import fcp.*;
import fcp.host.*;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Deployment-only restricted MySQL DDL. Every statement is generated from a closed enum.
 * DDL is NOT wrapped in a transaction. Intent survives implicit commits; retry inspects actual
 * information_schema before deciding whether a step is complete. Credentials never reach plugins.
 * This provisions physical structures; generic Session remains the separately scoped JSON API.
 */
public final class PhysicalSchemaMigrator {
    public enum Type {
        TEXT("VARCHAR(512)","varchar",512), INTEGER("BIGINT","bigint",0), DECIMAL("DECIMAL(20,4)","decimal",0), BOOLEAN("TINYINT","tinyint",0), TIMESTAMP("DATETIME(6)","datetime",0), JSON("JSON","json",0);
        final String sql,dataType;final int length;Type(String s,String d,int l){sql=s;dataType=d;length=l;}
    }
    public enum Kind { CREATE, ADD_NULLABLE_FIELD, ADD_INDEX }
    public record Step(long version,Kind kind,String field,Type type,List<String> indexFields){
        public Step(long version,Kind kind,String field,Type type){this(version,kind,field,type,kind==Kind.ADD_INDEX?List.of(field):List.of());}
        public Step {indexFields=List.copyOf(indexFields);if(kind==Kind.ADD_INDEX){if(indexFields.isEmpty()||indexFields.size()>4)throw new IllegalArgumentException("Index size");indexFields.forEach(PhysicalSchemaMigrator::fieldName);}else if(!indexFields.isEmpty())throw new IllegalArgumentException("Unexpected index fields");if(version<1)throw new IllegalArgumentException("Version");Objects.requireNonNull(kind);if(kind==Kind.CREATE){if(field!=null||type!=null)throw new IllegalArgumentException("CREATE has no field");}else{fieldName(field);if(kind==Kind.ADD_NULLABLE_FIELD)Objects.requireNonNull(type);else if(type!=null)throw new IllegalArgumentException("Index has no type");}}
    }
    public record Plan(String plugin,String structure,String table,List<Step> steps,String checksum){public Plan{steps=List.copyOf(steps);}}
    private final DataSource deployment;private final HostCapabilities host;
    public PhysicalSchemaMigrator(DataSource deploymentCredentials,HostCapabilities host){deployment=Objects.requireNonNull(deploymentCredentials);this.host=Objects.requireNonNull(host);}
    public static Plan plan(String plugin,String structure,List<Step> steps){
        StorageSchema.pluginIdentifier(plugin);StorageSchema.identifier(structure);if(steps.isEmpty()||steps.size()>64)throw new IllegalArgumentException("Step count");
        long last=0;for(Step s:steps){if(s.version()<=last)throw new IllegalArgumentException("Nonascending steps");last=s.version();}
        String table="fcp_p_"+JdbcPluginStorage.hash(plugin).substring(0,16)+"_"+JdbcPluginStorage.hash(structure).substring(0,16);
        String checksum=JdbcPluginStorage.hash(Json.write(List.of(plugin,structure,steps.stream().map(s->Arrays.asList(s.version(),s.kind().name(),s.field(),s.type()==null?null:s.type().name(),s.indexFields())).toList())));
        return new Plan(plugin,structure,table,steps,checksum);
    }
    /** Must be called only after administrator confirmation of this exact plan digest. */
    public void apply(TrustedContext ctx,Plan supplied,String confirmedDigest){
        Plan p=plan(supplied.plugin(),supplied.structure(),supplied.steps());if(!p.equals(supplied)||!p.checksum().equals(confirmedDigest))throw JdbcPluginStorage.error("CONFIRMATION_REQUIRED");
        var grant=new HostCapabilities.Grant(p.plugin(),"storage.schema","migrate","collection:"+p.structure());host.require(ctx,grant);
        try(Connection c=deployment.getConnection()){
            c.setAutoCommit(true);
            // MySQL connection-scoped advisory lock serializes only this registered structure.
            String lock="fcp-ddl:"+p.table();boolean acquired=false;
            try{
                try(PreparedStatement s=statement(c,"SELECT GET_LOCK(?,0)")){s.setString(1,lock);try(ResultSet r=s.executeQuery()){if(!r.next()||r.getInt(1)!=1)throw JdbcPluginStorage.error("MIGRATION_BUSY");acquired=true;}}
                register(c,p);
                for(Step step:p.steps()){host.require(ctx,grant);applyStep(c,p,step,ctx);}
            }finally{if(acquired)try(PreparedStatement s=statement(c,"SELECT RELEASE_LOCK(?)")){s.setString(1,lock);s.executeQuery().close();}}
        }catch(SQLException e){throw JdbcPluginStorage.failure(e);}
    }
    private static void register(Connection c,Plan p)throws SQLException{
        try(PreparedStatement s=statement(c,"INSERT INTO fcp_plugin_physical_schema(table_name,plugin_id,structure_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE table_name=table_name")){s.setString(1,p.table());s.setString(2,p.plugin());s.setString(3,p.structure());s.executeUpdate();}
        try(PreparedStatement s=statement(c,"SELECT plugin_id,structure_id FROM fcp_plugin_physical_schema WHERE table_name=?")){s.setString(1,p.table());try(ResultSet r=s.executeQuery()){if(!r.next()||!p.plugin().equals(r.getString(1))||!p.structure().equals(r.getString(2)))throw JdbcPluginStorage.error("OWNERSHIP_CONFLICT");}}
    }
    private static void applyStep(Connection c,Plan p,Step step,TrustedContext ctx)throws SQLException{
        String sql=sql(p.table(),step);String checksum=JdbcPluginStorage.hash(sql);String id=JdbcPluginStorage.hash(p.table()+"\n"+step.version());
        boolean exists=false;boolean complete=false;
        try(PreparedStatement s=statement(c,"SELECT checksum,phase FROM fcp_plugin_ddl_step WHERE step_id=?")){s.setString(1,id);try(ResultSet r=s.executeQuery()){if(r.next()){exists=true;complete="COMPLETE".equals(r.getString(2));if(!checksum.equals(r.getString(1)))throw JdbcPluginStorage.error("MIGRATION_CONFLICT");}}}
        if(!exists){
            long latest=0;try(PreparedStatement s=statement(c,"SELECT COALESCE(MAX(target_version),0) FROM fcp_plugin_ddl_step WHERE table_name=? AND phase='COMPLETE'")){s.setString(1,p.table());try(ResultSet r=s.executeQuery()){r.next();latest=r.getLong(1);}}
            if(step.version()!=latest+1)throw JdbcPluginStorage.error("MIGRATION_ORDER_CONFLICT");
            if(present(c,p.table(),step))throw JdbcPluginStorage.error("UNJOURNALED_SCHEMA_CONFLICT");
            String before=snapshot(c,p.table());
            try(PreparedStatement s=statement(c,"INSERT INTO fcp_plugin_ddl_step(step_id,table_name,target_version,checksum,phase,before_digest,principal_id,request_id) VALUES(?,?,?,?,'INTENT',?,?,?)")){s.setString(1,id);s.setString(2,p.table());s.setLong(3,step.version());s.setString(4,checksum);s.setString(5,before);s.setString(6,ctx.principal().id());s.setString(7,ctx.requestId());s.executeUpdate();}
        }
        try {
            if(!present(c,p.table(),step)){
                if(complete)throw JdbcPluginStorage.error("PHYSICAL_SCHEMA_DRIFT");
                if(step.kind()!=Kind.CREATE && !present(c,p.table(),new Step(1,Kind.CREATE,null,null)))throw JdbcPluginStorage.error("MISSING_PHYSICAL_TABLE");
                if(step.kind()==Kind.ADD_INDEX)for(String field:step.indexFields())if(!hasColumn(c,p.table(),field))throw JdbcPluginStorage.error("UNDECLARED_INDEX_FIELD");
                try(Statement s=c.createStatement()){s.setQueryTimeout(30);s.execute(sql);}
            }
            if(!present(c,p.table(),step))throw JdbcPluginStorage.error("SCHEMA_VERIFICATION_FAILED");
            try(PreparedStatement s=statement(c,"UPDATE fcp_plugin_ddl_step SET phase='COMPLETE',after_digest=?,updated_at=CURRENT_TIMESTAMP(6) WHERE step_id=?")){s.setString(1,snapshot(c,p.table()));s.setString(2,id);s.executeUpdate();}
        }catch(SQLException|RuntimeException e){
            // FAILED means reconciliation required, never that MySQL rolled back DDL.
            try(PreparedStatement s=statement(c,"UPDATE fcp_plugin_ddl_step SET phase='FAILED',updated_at=CURRENT_TIMESTAMP(6) WHERE step_id=?")){s.setString(1,id);s.executeUpdate();}catch(SQLException ignored){}
            throw e;
        }
    }
    static String sql(String table,Step s){return switch(s.kind()){
        case CREATE -> "CREATE TABLE `"+table+"` (tenant_id VARCHAR(128) NOT NULL,data_scope VARCHAR(192) NOT NULL,record_id VARCHAR(64) NOT NULL,revision BIGINT NOT NULL,del TINYINT NOT NULL DEFAULT 0,PRIMARY KEY(tenant_id,data_scope,record_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin";
        case ADD_NULLABLE_FIELD -> "ALTER TABLE `"+table+"` ADD COLUMN `"+s.field()+"` "+s.type().sql+" NULL";
        case ADD_INDEX -> "CREATE INDEX `"+index(s.field())+"` ON `"+table+"` ("+String.join(",",s.indexFields().stream().map(f->"`"+f+"`").toList())+")";
    };}
    static boolean present(Connection c,String table,Step step)throws SQLException{
        if(step.kind()==Kind.CREATE){try(PreparedStatement s=statement(c,"SELECT ENGINE,TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")){s.setString(1,table);try(ResultSet r=s.executeQuery()){if(!r.next())return false;if(!"InnoDB".equalsIgnoreCase(r.getString(1))||!"utf8mb4_bin".equalsIgnoreCase(r.getString(2)))throw JdbcPluginStorage.error("PHYSICAL_SCHEMA_DRIFT");}}
            Map<String,String> expected=Map.of("tenant_id","varchar","data_scope","varchar","record_id","varchar","revision","bigint","del","tinyint");
            Map<String,Integer> lengths=Map.of("tenant_id",128,"data_scope",192,"record_id",64);
            for(String f:expected.keySet())try(PreparedStatement s=statement(c,"SELECT DATA_TYPE,IS_NULLABLE,CHARACTER_MAXIMUM_LENGTH,COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?")){s.setString(1,table);s.setString(2,f);try(ResultSet r=s.executeQuery()){if(!r.next()||!expected.get(f).equalsIgnoreCase(r.getString(1))||!"NO".equals(r.getString(2))||(lengths.containsKey(f)&&(r.getInt(3)!=lengths.get(f)||!"utf8mb4_bin".equalsIgnoreCase(r.getString(4)))))throw JdbcPluginStorage.error("PHYSICAL_SCHEMA_DRIFT");}}
            try(PreparedStatement s=statement(c,"SELECT COLUMN_NAME,SUB_PART FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME='PRIMARY' ORDER BY SEQ_IN_INDEX")){s.setString(1,table);try(ResultSet r=s.executeQuery()){List<String> key=new ArrayList<>();while(r.next()){if(r.getObject(2)!=null)throw JdbcPluginStorage.error("PHYSICAL_SCHEMA_DRIFT");key.add(r.getString(1));}if(!key.equals(List.of("tenant_id","data_scope","record_id")))throw JdbcPluginStorage.error("PHYSICAL_SCHEMA_DRIFT");}}return true;
        }
        if(step.kind()==Kind.ADD_INDEX){try(PreparedStatement s=statement(c,"SELECT COLUMN_NAME,SEQ_IN_INDEX,NON_UNIQUE,SUB_PART FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=? ORDER BY SEQ_IN_INDEX")){s.setString(1,table);s.setString(2,index(step.field()));try(ResultSet r=s.executeQuery()){List<String> fields=new ArrayList<>();while(r.next()){if(r.getInt(2)!=fields.size()+1||r.getInt(3)!=1||r.getObject(4)!=null)throw JdbcPluginStorage.error("PHYSICAL_SCHEMA_DRIFT");fields.add(r.getString(1));}if(fields.isEmpty())return false;if(!fields.equals(step.indexFields()))throw JdbcPluginStorage.error("PHYSICAL_SCHEMA_DRIFT");return true;}}}
        try(PreparedStatement s=statement(c,"SELECT DATA_TYPE,IS_NULLABLE,CHARACTER_MAXIMUM_LENGTH,NUMERIC_PRECISION,NUMERIC_SCALE,DATETIME_PRECISION,COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?")){s.setString(1,table);s.setString(2,step.field());try(ResultSet r=s.executeQuery()){if(!r.next())return false;boolean ok=step.type().dataType.equalsIgnoreCase(r.getString(1))&&"YES".equals(r.getString(2));if(step.type()==Type.TEXT)ok&=r.getInt(3)==512&&"utf8mb4_bin".equalsIgnoreCase(r.getString(7));if(step.type()==Type.DECIMAL)ok&=r.getInt(4)==20&&r.getInt(5)==4;if(step.type()==Type.TIMESTAMP)ok&=r.getInt(6)==6;if(!ok)throw JdbcPluginStorage.error("PHYSICAL_SCHEMA_DRIFT");return true;}}
    }
    private static boolean hasColumn(Connection c,String table,String field)throws SQLException{try(PreparedStatement s=statement(c,"SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?")){s.setString(1,table);s.setString(2,field);try(ResultSet r=s.executeQuery()){return r.next();}}}
    private static String snapshot(Connection c,String table)throws SQLException{List<Object> rows=new ArrayList<>();try(PreparedStatement s=statement(c,"SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION")){s.setString(1,table);try(ResultSet r=s.executeQuery()){while(r.next())rows.add(List.of(r.getString(1),r.getString(2),r.getString(3)));}}return JdbcPluginStorage.hash(Json.write(rows));}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{PreparedStatement s=c.prepareStatement(sql);s.setQueryTimeout(30);return s;}
    private static String index(String field){return "idx_"+JdbcPluginStorage.hash(field).substring(0,16);}
    private static void fieldName(String field){if(field==null||!field.matches("[a-z][a-z0-9_]{0,47}")||Set.of("tenant_id","data_scope","record_id","revision","del").contains(field))throw new IllegalArgumentException("Invalid physical field");}
}
