package fcp.storage;

import fcp.*;
import fcp.host.*;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Host deployment-only logical schema migration. Generic records need no per-plugin DDL.
 * Shared physical tables are provisioned with V005 using separate deployment credentials.
 * No arbitrary SQL, destructive field edits, or physical-table migrations are accepted here.
 * INTENT is separately committed before the atomic metadata+COMPLETE transaction; recovery
 * revalidates actual schema/checksum and retries the same additive plan. No DDL rollback claim.
 */
public final class AdditiveSchemaMigrator {
    private final DataSource deployment;private final HostCapabilities host;
    public AdditiveSchemaMigrator(DataSource deploymentCredentials,HostCapabilities host){deployment=Objects.requireNonNull(deploymentCredentials);this.host=Objects.requireNonNull(host);}
    public void apply(TrustedContext context,String plugin,StorageSchema next,long expectedVersion){
        StorageSchema.pluginIdentifier(plugin);if(expectedVersion<0||next.version()!=expectedVersion+1)throw new IllegalArgumentException("Sequential schema version required");
        var grant=new HostCapabilities.Grant(plugin,"storage.schema","migrate","collection:"+next.collection());host.require(context,grant);
        String checksum=JdbcPluginStorage.hash(next.json());String key=JdbcPluginStorage.hash(plugin+"\n"+next.collection()+"\n"+next.version());
        try(Connection c=deployment.getConnection()){
            c.setAutoCommit(true);
            try(PreparedStatement p=statement(c,"SELECT table_name FROM fcp_plugin_physical_schema WHERE plugin_id=? AND structure_id=?")){p.setString(1,plugin);p.setString(2,next.collection());try(ResultSet r=p.executeQuery()){if(r.next())throw JdbcPluginStorage.error("STORAGE_KIND_CONFLICT");}}
            // This connection must not be shared with the runtime DML identity by the host.
            try(PreparedStatement p=statement(c,"INSERT INTO fcp_plugin_migration(step_id,plugin_id,collection_id,target_version,checksum,phase) VALUES(?,?,?,?,?,'INTENT') ON DUPLICATE KEY UPDATE step_id=step_id")){p.setString(1,key);p.setString(2,plugin);p.setString(3,next.collection());p.setLong(4,next.version());p.setString(5,checksum);p.executeUpdate();}
            c.setAutoCommit(false);
            try{
                try(PreparedStatement p=statement(c,"SELECT checksum,phase FROM fcp_plugin_migration WHERE step_id=? FOR UPDATE")){p.setString(1,key);try(ResultSet r=p.executeQuery()){if(!r.next()||!checksum.equals(r.getString(1)))throw JdbcPluginStorage.error("MIGRATION_CONFLICT");}}
                StorageSchema old=null;
                try(PreparedStatement p=statement(c,"SELECT schema_json FROM fcp_plugin_schema WHERE plugin_id=? AND collection_id=? FOR UPDATE")){p.setString(1,plugin);p.setString(2,next.collection());try(ResultSet r=p.executeQuery()){if(r.next())old=StorageSchema.parse(r.getString(1));}}
                if(old!=null&&old.version()==next.version()){if(!old.json().equals(next.json()))throw JdbcPluginStorage.error("MIGRATION_CONFLICT");}
                else {
                    if((old==null?0:old.version())!=expectedVersion)throw JdbcPluginStorage.error("VERSION_CONFLICT");
                    if(old!=null){if(!next.fields().entrySet().containsAll(old.fields().entrySet())||!next.uniqueFields().equals(old.uniqueFields()))throw JdbcPluginStorage.error("DESTRUCTIVE_MIGRATION_REJECTED");}
                    if(old==null)try(PreparedStatement p=statement(c,"INSERT INTO fcp_plugin_schema(plugin_id,collection_id,schema_version,schema_json) VALUES(?,?,?,?)")){p.setString(1,plugin);p.setString(2,next.collection());p.setLong(3,next.version());p.setString(4,next.json());p.executeUpdate();}
                    else try(PreparedStatement p=statement(c,"UPDATE fcp_plugin_schema SET schema_version=?,schema_json=? WHERE plugin_id=? AND collection_id=? AND schema_version=?")){p.setLong(1,next.version());p.setString(2,next.json());p.setString(3,plugin);p.setString(4,next.collection());p.setLong(5,expectedVersion);if(p.executeUpdate()!=1)throw JdbcPluginStorage.error("VERSION_CONFLICT");}
                }
                try(PreparedStatement p=statement(c,"UPDATE fcp_plugin_migration SET phase='COMPLETE',updated_at=CURRENT_TIMESTAMP(6) WHERE step_id=?")){p.setString(1,key);p.executeUpdate();}
                host.require(context,grant);c.commit();
            }catch(SQLException|RuntimeException e){try{c.rollback();}catch(SQLException ignored){}throw e;}
        }catch(SQLException e){throw JdbcPluginStorage.failure(e);}
    }
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{PreparedStatement s=c.prepareStatement(sql);s.setQueryTimeout(30);return s;}

}
