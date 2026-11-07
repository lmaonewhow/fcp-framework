package fcp.storage;

import fcp.*;
import fcp.host.*;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Administrator-only reviewed deployment action. Never expose this object as plugin capability.
 * The supplied DataSource is the separate deployment identity, not the runtime DML identity.
 */
public final class StorageDeploymentService {
    public record Plan(String plugin,long expectedVersion,StorageDeclaration declaration,List<PhysicalSchemaMigrator.Plan> tables,String digest){public Plan{tables=List.copyOf(tables);}}
    private final DataSource deployment;private final HostCapabilities host;
    public StorageDeploymentService(DataSource deployment,HostCapabilities host){this.deployment=Objects.requireNonNull(deployment);this.host=Objects.requireNonNull(host);}
    public Plan plan(String plugin,Map<String,Object> declaration){
        StorageSchema.pluginIdentifier(plugin);StorageDeclaration next=StorageDeclaration.parse(declaration);
        try(Connection c=deployment.getConnection()){
            StorageDeclaration old=current(c,plugin);long expected=old==null?0:old.version();
            if(old!=null&&old.version()==next.version()){if(!old.digest().equals(next.digest()))throw JdbcPluginStorage.error("SCHEMA_VERSION_CONFLICT");return make(plugin,expected,next,List.of());}
            if(next.version()!=expected+1)throw JdbcPluginStorage.error("MIGRATION_ORDER_CONFLICT");
            if(old!=null)validateAdditive(old,next);
            List<PhysicalSchemaMigrator.Plan> tables=new ArrayList<>();
            for(var table:next.tables().values()){
                try(PreparedStatement p=statement(c,"SELECT schema_version FROM fcp_plugin_schema WHERE plugin_id=? AND collection_id=?")){p.setString(1,plugin);p.setString(2,table.name());try(ResultSet r=p.executeQuery()){if(r.next())throw JdbcPluginStorage.error("STORAGE_KIND_CONFLICT");}}
                StorageDeclaration.Table before=old==null?null:old.tables().get(table.name());long revision=0;
                try(PreparedStatement p=statement(c,"SELECT ddl_revision FROM fcp_plugin_physical_schema WHERE plugin_id=? AND structure_id=?")){p.setString(1,plugin);p.setString(2,table.name());try(ResultSet r=p.executeQuery()){if(r.next())revision=r.getLong(1);}}
                List<PhysicalSchemaMigrator.Step> steps=new ArrayList<>();if(before==null)steps.add(new PhysicalSchemaMigrator.Step(++revision,PhysicalSchemaMigrator.Kind.CREATE,null,null));
                for(var field:table.fields().values())if(before==null||!before.fields().containsKey(field.name()))steps.add(new PhysicalSchemaMigrator.Step(++revision,PhysicalSchemaMigrator.Kind.ADD_NULLABLE_FIELD,field.name(),field.type()));
                for(var index:table.indexes())if(before==null||before.indexes().stream().noneMatch(i->i.name().equals(index.name())))steps.add(new PhysicalSchemaMigrator.Step(++revision,PhysicalSchemaMigrator.Kind.ADD_INDEX,index.name(),null,index.fields()));
                if(!steps.isEmpty())tables.add(PhysicalSchemaMigrator.plan(plugin,table.name(),steps));
            }
            return make(plugin,expected,next,tables);
        }catch(SQLException e){throw JdbcPluginStorage.failure(e);}
    }
    public void apply(TrustedContext context,Plan supplied,String confirmedDigest){
        if(!supplied.digest().equals(confirmedDigest))throw JdbcPluginStorage.error("CONFIRMATION_REQUIRED");
        var grant=new HostCapabilities.Grant(supplied.plugin(),"storage.schema","migrate","tenant:"+context.tenantId());host.require(context,grant);
        try(Connection c=deployment.getConnection()){
            c.setAutoCommit(true);String lock="fcp-schema:"+JdbcPluginStorage.hash(supplied.plugin()).substring(0,40);boolean locked=false;
            try{
                try(PreparedStatement p=statement(c,"SELECT GET_LOCK(?,0)")){p.setString(1,lock);try(ResultSet r=p.executeQuery()){if(!r.next()||r.getInt(1)!=1)throw JdbcPluginStorage.error("MIGRATION_BUSY");locked=true;}}
                StorageDeclaration actual=current(c,supplied.plugin());
                if(actual!=null&&actual.version()==supplied.declaration().version()&&actual.digest().equals(supplied.declaration().digest())){new JdbcStorageCompatibility(deployment).verify(supplied.plugin(),"deployment","",actual.source());return;}
                Plan checked=plan(supplied.plugin(),supplied.declaration().source());if(!checked.equals(supplied))throw JdbcPluginStorage.error("PLAN_STALE");
                for(var table:supplied.tables()){host.require(context,grant);new PhysicalSchemaMigrator(deployment,host).apply(context,table,table.checksum());}
                // Publish descriptions only after all DDL and actual-structure checks succeed.
                c.setAutoCommit(false);
                try{
                    for(var table:supplied.declaration().tables().values()){
                        try(PreparedStatement lockRow=statement(c,"SELECT table_name FROM fcp_plugin_physical_schema WHERE plugin_id=? AND structure_id=? FOR UPDATE")){lockRow.setString(1,supplied.plugin());lockRow.setString(2,table.name());try(ResultSet lockedRow=lockRow.executeQuery()){if(!lockedRow.next())throw JdbcPluginStorage.error("SCHEMA_NOT_REGISTERED");}}
                        checkUniqueData(c,supplied.plugin(),table);

                        long revision=0;for(var p:supplied.tables())if(p.structure().equals(table.name()))revision=p.steps().get(p.steps().size()-1).version();
                        String sql=revision==0?"UPDATE fcp_plugin_physical_schema SET schema_json=?,schema_version=? WHERE plugin_id=? AND structure_id=?":"UPDATE fcp_plugin_physical_schema SET schema_json=?,schema_version=?,ddl_revision=? WHERE plugin_id=? AND structure_id=?";
                        try(PreparedStatement p=statement(c,sql)){p.setString(1,StorageDeclaration.canonical(table.source()));p.setLong(2,supplied.declaration().version());int i=3;if(revision>0)p.setLong(i++,revision);p.setString(i++,supplied.plugin());p.setString(i,table.name());if(p.executeUpdate()!=1)throw JdbcPluginStorage.error("SCHEMA_NOT_REGISTERED");}
                    }
                    if(actual==null)try(PreparedStatement p=statement(c,"INSERT INTO fcp_plugin_storage_release(plugin_id,schema_version,declaration_json,declaration_digest) VALUES(?,?,?,?)")){p.setString(1,supplied.plugin());p.setLong(2,supplied.declaration().version());p.setString(3,supplied.declaration().json());p.setString(4,supplied.declaration().digest());p.executeUpdate();}
                    else try(PreparedStatement p=statement(c,"UPDATE fcp_plugin_storage_release SET schema_version=?,declaration_json=?,declaration_digest=? WHERE plugin_id=? AND schema_version=?")){p.setLong(1,supplied.declaration().version());p.setString(2,supplied.declaration().json());p.setString(3,supplied.declaration().digest());p.setString(4,supplied.plugin());p.setLong(5,supplied.expectedVersion());if(p.executeUpdate()!=1)throw JdbcPluginStorage.error("VERSION_CONFLICT");}
                    try(PreparedStatement audit=statement(c,"INSERT INTO fcp_runtime_audit(request_id,trace_id,tenant_id,principal_id,plugin_id,action,outcome,occurred_at) VALUES(?,?,?,?,?,?,?,?)")){audit.setString(1,context.requestId());audit.setString(2,context.traceId());audit.setString(3,context.tenantId());audit.setString(4,context.principal().id());audit.setString(5,supplied.plugin());audit.setString(6,"storage.deploy");audit.setString(7,"COMPLETE:"+supplied.declaration().version());audit.setTimestamp(8,Timestamp.from(host.clock().instant()));audit.executeUpdate();}
                    host.require(context,grant);c.commit();
                }catch(SQLException|RuntimeException e){try{c.rollback();}catch(SQLException ignored){}throw e;}
            }finally{if(locked)try(PreparedStatement p=statement(c,"SELECT RELEASE_LOCK(?)")){p.setString(1,lock);p.executeQuery().close();}}
        }catch(SQLException e){throw new FcpException("MIGRATION_OUTCOME_UNKNOWN","Inspect deployment journal and retry the exact confirmed plan");}
    }
    /** Metadata lock held while checking and publishing uniqueness. Runtime mutations take it too. */
    private static void checkUniqueData(Connection c,String plugin,StorageDeclaration.Table table)throws SQLException{
        String name=PhysicalSchemaMigrator.plan(plugin,table.name(),List.of(new PhysicalSchemaMigrator.Step(1,PhysicalSchemaMigrator.Kind.CREATE,null,null))).table();
        for(var index:table.indexes())if(index.unique()){
            String fields=String.join(",",index.fields().stream().map(f->"`"+f+"`").toList());
            String nonnull=String.join(" AND ",index.fields().stream().map(f->"`"+f+"` IS NOT NULL").toList());
            try(PreparedStatement p=statement(c,"SELECT 1 FROM `"+name+"` WHERE del=0 AND "+nonnull+" GROUP BY tenant_id,data_scope,"+fields+" HAVING COUNT(*)>1 LIMIT 1")){try(ResultSet r=p.executeQuery()){if(r.next())throw JdbcPluginStorage.error("UNIQUE_MIGRATION_CONFLICT");}}
        }
    }
    private static Plan make(String plugin,long expected,StorageDeclaration next,List<PhysicalSchemaMigrator.Plan> tables){return new Plan(plugin,expected,next,tables,JdbcPluginStorage.hash(Json.write(List.of(plugin,expected,next.digest(),tables.stream().map(PhysicalSchemaMigrator.Plan::checksum).toList()))));}
    static StorageDeclaration current(Connection c,String plugin)throws SQLException{try(PreparedStatement p=statement(c,"SELECT declaration_json,declaration_digest FROM fcp_plugin_storage_release WHERE plugin_id=?")){p.setString(1,plugin);try(ResultSet r=p.executeQuery()){if(!r.next())return null;StorageDeclaration d=StorageDeclaration.parse(StorageDeclaration.object(Json.parse(r.getString(1))));if(!d.digest().equals(r.getString(2)))throw JdbcPluginStorage.error("SCHEMA_METADATA_DRIFT");return d;}}}
    static void validateAdditive(StorageDeclaration old,StorageDeclaration next){
        if(!next.tables().keySet().containsAll(old.tables().keySet()))throw JdbcPluginStorage.error("DESTRUCTIVE_MIGRATION_REJECTED");
        Set<String> additions=new TreeSet<>();
        for(var before:old.tables().values()){
            var after=next.tables().get(before.name());if(!after.fields().entrySet().containsAll(before.fields().entrySet())||!after.indexes().containsAll(before.indexes()))throw JdbcPluginStorage.error("DESTRUCTIVE_MIGRATION_REJECTED");
            for(var f:after.fields().values())if(!before.fields().containsKey(f.name())){if(!f.nullable())throw JdbcPluginStorage.error("DESTRUCTIVE_MIGRATION_REJECTED");additions.add(before.name()+"."+f.name());}
        }
        Set<String> declared=new TreeSet<>();for(Object value:StorageDeclaration.list(next.source().getOrDefault("migrations",List.of()))){var m=StorageDeclaration.object(value);if(StorageDeclaration.integer(m.get("from"))==old.version()&&StorageDeclaration.integer(m.get("to"))==next.version())for(Object op:StorageDeclaration.list(m.get("operations"))){var o=StorageDeclaration.object(op);declared.add(o.get("table")+"."+o.get("field"));}}
        if(!declared.equals(additions))throw JdbcPluginStorage.error("MIGRATION_DECLARATION_MISMATCH");
    }
    static PreparedStatement statement(Connection c,String sql)throws SQLException{PreparedStatement p=c.prepareStatement(sql);p.setQueryTimeout(30);return p;}
}
