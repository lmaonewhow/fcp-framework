package dev.fcp.school;

import fcp.FcpException;
import fcp.state.PluginStateStore;
import javax.sql.DataSource;
import java.sql.*;
import java.util.Objects;

/** MySQL desired state. Runtime credentials need only DML on these two tables.
 * Updates and their audit event share one local transaction. No runtime DDL.
 */
public final class JdbcPluginStateStore implements PluginStateStore {
    private final DataSource dataSource;
    public JdbcPluginStateStore(DataSource dataSource) { this.dataSource=Objects.requireNonNull(dataSource); }
    private static void bind(PreparedStatement statement,Object...args)throws SQLException {
        statement.setQueryTimeout(5);
        for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);
    }
    public State getOrCreate(Key key,boolean initialEnabled) {
        try(Connection connection=dataSource.getConnection()) {
            if(!connection.getAutoCommit())throw new FcpException("TRANSACTION_MODE","Dedicated auto-commit connection required");
            State existing=read(connection,key);
            if(existing!=null)return existing;
            try(PreparedStatement insert=connection.prepareStatement(
                "INSERT INTO fcp_plugin_state(scope_id,plugin_id,enabled,revision,updated_by) VALUES(?,?,?,1,'system') ON DUPLICATE KEY UPDATE revision=revision")){
                bind(insert,key.scope(),key.plugin(),initialEnabled);insert.executeUpdate();
            }
            State created=read(connection,key);
            if(created==null)throw new FcpException("STATE_UNAVAILABLE","Plugin state disappeared");
            return created;
        }catch(SQLException failure){throw new FcpException("STATE_UNAVAILABLE","Unable to read authoritative plugin state",failure);}
    }
    private State read(Connection connection,Key key)throws SQLException {
        try(PreparedStatement query=connection.prepareStatement(
            "SELECT enabled,revision FROM fcp_plugin_state WHERE scope_id=? AND plugin_id=?")){
            bind(query,key.scope(),key.plugin());
            try(ResultSet result=query.executeQuery()){
                return result.next()?new State(result.getBoolean(1),result.getLong(2)):null;
            }
        }
    }
    public State compareAndSet(Key key,long expectedRevision,boolean enabled,String actor) {
        if(actor==null || actor.isBlank() || actor.length()>100 || expectedRevision<1)throw new IllegalArgumentException("Invalid state update");
        long nextRevision=Math.addExact(expectedRevision,1);
        Connection connection;
        try{connection=dataSource.getConnection();}catch(SQLException e){throw new FcpException("STATE_UNAVAILABLE","State connection unavailable",e);}
        boolean begun=false,settled=false,commitAttempted=false;
        try {
            if(!connection.getAutoCommit())throw new FcpException("TRANSACTION_MODE","Ambient transactions are unsupported");
            connection.setAutoCommit(false);begun=true;
            try(PreparedStatement update=connection.prepareStatement(
                "UPDATE fcp_plugin_state SET enabled=?,revision=?,updated_by=?,updated_at=CURRENT_TIMESTAMP(6) WHERE scope_id=? AND plugin_id=? AND revision=?")){
                bind(update,enabled,nextRevision,actor,key.scope(),key.plugin(),expectedRevision);
                if(update.executeUpdate()!=1)throw new FcpException("STALE_COMPOSITION","Persisted plugin revision changed");
            }
            try(PreparedStatement audit=connection.prepareStatement(
                "INSERT INTO fcp_plugin_state_audit(scope_id,plugin_id,revision,enabled,actor) VALUES(?,?,?,?,?)")){
                bind(audit,key.scope(),key.plugin(),nextRevision,enabled,actor);audit.executeUpdate();
            }
            commitAttempted=true;connection.commit();settled=true;
            return new State(enabled,nextRevision);
        } catch(SQLException failure){throw new FcpException("STATE_UNAVAILABLE","Plugin update outcome requires state refresh",failure);}
        finally {
            if(begun && !settled && !commitAttempted){try{connection.rollback();settled=true;}catch(SQLException ignored){/* abort below */}}
            try {
                if(begun && !settled)connection.abort(Runnable::run);
                else if(begun)connection.setAutoCommit(true);
            }catch(SQLException reset){try{connection.abort(Runnable::run);}catch(SQLException ignored){/* close below */}}
            try{connection.close();}catch(SQLException ignored){/* pool must discard aborted connections */}
        }
    }
}
