package fcp.storage;

import fcp.*;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Read-only runtime verification. Does not create tables, run migrations, or change readiness. */
public final class JdbcStorageCompatibility {
    private final DataSource runtime;
    public JdbcStorageCompatibility(DataSource runtime){this.runtime=Objects.requireNonNull(runtime);}
    public void verify(String plugin,String packageVersion,String packageDigest,Map<String,Object> storage){
        if(storage.isEmpty())return;StorageSchema.pluginIdentifier(plugin);StorageDeclaration wanted=StorageDeclaration.parse(storage);
        try(Connection c=runtime.getConnection()){
            StorageDeclaration actual=StorageDeploymentService.current(c,plugin);if(actual==null)throw JdbcPluginStorage.error("STORAGE_NOT_DEPLOYED");
            if(actual.version()!=wanted.version()&&!wanted.compatibleVersions().contains(actual.version()))throw JdbcPluginStorage.error("STORAGE_VERSION_INCOMPATIBLE");
            if(actual.version()==wanted.version()&&!actual.digest().equals(wanted.digest()))throw JdbcPluginStorage.error("SCHEMA_VERSION_CONFLICT");
            for(var table:wanted.tables().values()){
                var current=actual.tables().get(table.name());if(current==null||!current.fields().entrySet().containsAll(table.fields().entrySet())||!current.indexes().containsAll(table.indexes()))throw JdbcPluginStorage.error("STORAGE_VERSION_INCOMPATIBLE");
            }
            for(var table:actual.tables().values())verifyTable(c,plugin,actual.version(),table);
        }catch(SQLException e){throw JdbcPluginStorage.failure(e);}
    }
    /** Read-only reviewed schema token. This is not a cross-process transaction with package switching. */
    public String fingerprint(String plugin,String packageVersion,String packageDigest,Map<String,Object> storage){
        if(storage.isEmpty())return "no-declared-schema";verify(plugin,packageVersion,packageDigest,storage);List<Object> material=new ArrayList<>();
        try(Connection c=runtime.getConnection()){
            try(PreparedStatement q=c.prepareStatement("SELECT schema_version,declaration_digest FROM fcp_plugin_storage_release WHERE plugin_id=?")){q.setQueryTimeout(5);q.setString(1,plugin);try(ResultSet r=q.executeQuery()){if(!r.next())throw JdbcPluginStorage.error("STORAGE_NOT_DEPLOYED");material.add(List.of(r.getLong(1),r.getString(2)));}}
            try(PreparedStatement q=c.prepareStatement("SELECT table_name,structure_id,schema_version,ddl_revision,schema_json FROM fcp_plugin_physical_schema WHERE plugin_id=? ORDER BY table_name")){q.setQueryTimeout(5);q.setString(1,plugin);try(ResultSet r=q.executeQuery()){while(r.next())material.add(List.of(r.getString(1),r.getString(2),r.getLong(3),r.getLong(4),StorageDeclaration.canonical(Json.parse(r.getString(5)))));}}
            try(PreparedStatement q=c.prepareStatement("SELECT d.table_name,d.target_version,d.checksum,d.phase,d.before_digest,COALESCE(d.after_digest,'') FROM fcp_plugin_ddl_step d JOIN fcp_plugin_physical_schema p ON p.table_name=d.table_name WHERE p.plugin_id=? ORDER BY d.table_name,d.target_version")){q.setQueryTimeout(5);q.setString(1,plugin);try(ResultSet r=q.executeQuery()){while(r.next())material.add(List.of(r.getString(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6)));}}
        }catch(SQLException e){throw JdbcPluginStorage.failure(e);}
        verify(plugin,packageVersion,packageDigest,storage);return JdbcPluginStorage.hash(Json.write(material));
    }
    static String verifyTable(Connection c,String plugin,long version,StorageDeclaration.Table table)throws SQLException{
        String name=PhysicalSchemaMigrator.plan(plugin,table.name(),List.of(new PhysicalSchemaMigrator.Step(1,PhysicalSchemaMigrator.Kind.CREATE,null,null))).table();long revision;
        try(PreparedStatement p=StorageDeploymentService.statement(c,"SELECT table_name,schema_version,schema_json,ddl_revision FROM fcp_plugin_physical_schema WHERE plugin_id=? AND structure_id=?")){p.setString(1,plugin);p.setString(2,table.name());try(ResultSet r=p.executeQuery()){if(!r.next()||!name.equals(r.getString(1))||version!=r.getLong(2)||!StorageDeclaration.canonical(table.source()).equals(StorageDeclaration.canonical(Json.parse(r.getString(3)))))throw JdbcPluginStorage.error("SCHEMA_METADATA_DRIFT");revision=r.getLong(4);}}
        try(PreparedStatement p=StorageDeploymentService.statement(c,"SELECT COUNT(*),COALESCE(MAX(target_version),0),SUM(CASE WHEN phase='COMPLETE' THEN 1 ELSE 0 END) FROM fcp_plugin_ddl_step WHERE table_name=?")){p.setString(1,name);try(ResultSet r=p.executeQuery()){r.next();if(revision<1||r.getLong(1)!=revision||r.getLong(2)!=revision||r.getLong(3)!=revision)throw JdbcPluginStorage.error("MIGRATION_INCOMPLETE");}}
        if(!PhysicalSchemaMigrator.present(c,name,new PhysicalSchemaMigrator.Step(1,PhysicalSchemaMigrator.Kind.CREATE,null,null)))throw JdbcPluginStorage.error("PHYSICAL_SCHEMA_DRIFT");
        for(var f:table.fields().values())if(!PhysicalSchemaMigrator.present(c,name,new PhysicalSchemaMigrator.Step(1,PhysicalSchemaMigrator.Kind.ADD_NULLABLE_FIELD,f.name(),f.type())))throw JdbcPluginStorage.error("PHYSICAL_SCHEMA_DRIFT");
        for(var i:table.indexes())if(!PhysicalSchemaMigrator.present(c,name,new PhysicalSchemaMigrator.Step(1,PhysicalSchemaMigrator.Kind.ADD_INDEX,i.name(),null,i.fields())))throw JdbcPluginStorage.error("PHYSICAL_SCHEMA_DRIFT");
        Set<String> expectedColumns=new TreeSet<>(table.fields().keySet());expectedColumns.addAll(List.of("tenant_id","data_scope","record_id","revision","del"));
        try(PreparedStatement p=StorageDeploymentService.statement(c,"SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")){p.setString(1,name);try(ResultSet r=p.executeQuery()){Set<String> actual=new TreeSet<>();while(r.next())actual.add(r.getString(1));if(!actual.equals(expectedColumns))throw JdbcPluginStorage.error("PHYSICAL_SCHEMA_DRIFT");}}
        Set<String> expectedIndexes=new TreeSet<>(Set.of("PRIMARY"));for(var index:table.indexes())expectedIndexes.add("idx_"+JdbcPluginStorage.hash(index.name()).substring(0,16));
        try(PreparedStatement p=StorageDeploymentService.statement(c,"SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")){p.setString(1,name);try(ResultSet r=p.executeQuery()){Set<String> actual=new TreeSet<>();while(r.next())actual.add(r.getString(1));if(!actual.equals(expectedIndexes))throw JdbcPluginStorage.error("PHYSICAL_SCHEMA_DRIFT");}}
        return name;
    }
}
