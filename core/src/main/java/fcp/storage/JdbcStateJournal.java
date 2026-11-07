package fcp.storage;

import fcp.control.StateJournal;
import javax.sql.DataSource;
import java.sql.*;
import java.io.IOException;
import java.util.Objects;

/** Durable CAS journal. This is DML only; table provisioning uses deployment credentials. */
public final class JdbcStateJournal implements StateJournal {
    private final DataSource source;private final String id;private boolean closed;
    public JdbcStateJournal(DataSource runtimeDml,String journalId){source=Objects.requireNonNull(runtimeDml);id=StorageSchema.identifier(journalId);}
    public synchronized Snapshot load() throws IOException {
        live();try(Connection c=source.getConnection()){c.setAutoCommit(true);try(PreparedStatement p=statement(c,"SELECT revision,OCTET_LENGTH(state_json),CASE WHEN OCTET_LENGTH(state_json)<=8388608 THEN state_json ELSE NULL END FROM fcp_runtime_state WHERE journal_id=?")){p.setString(1,id);try(ResultSet r=p.executeQuery()){if(!r.next())return new Snapshot(0,null);if(r.getLong(2)>8388608)throw new IOException("JOURNAL_SIZE_LIMIT");return new Snapshot(r.getLong(1),r.getString(3));}}}catch(SQLException e){throw new IOException("JOURNAL_UNAVAILABLE",e);}
    }
    public synchronized Snapshot compareAndSet(long expected,String json)throws IOException{
        live();if(expected<0||expected==Long.MAX_VALUE||json==null||json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8*1024*1024)throw new IOException("INVALID_JOURNAL");fcp.Json.parse(json);
        boolean commit=false;
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try{
                if(expected==0)try(PreparedStatement p=statement(c,"INSERT INTO fcp_runtime_state(journal_id,revision,state_json) VALUES(?,1,?)")){p.setString(1,id);p.setString(2,json);p.executeUpdate();}
                else try(PreparedStatement p=statement(c,"UPDATE fcp_runtime_state SET revision=revision+1,state_json=? WHERE journal_id=? AND revision=?")){p.setString(1,json);p.setString(2,id);p.setLong(3,expected);if(p.executeUpdate()!=1)throw new IOException("JOURNAL_CONFLICT");}
                commit=true;c.commit();return new Snapshot(expected+1,json);
            }catch(SQLException|IOException e){try{c.rollback();}catch(SQLException ignored){}throw e;}
        }catch(SQLException e){throw new IOException(commit?"JOURNAL_OUTCOME_UNKNOWN":e.getSQLState()!=null&&e.getSQLState().startsWith("23")?"JOURNAL_CONFLICT":"JOURNAL_UNAVAILABLE",e);}
    }
    private void live()throws IOException{if(closed)throw new IOException("JOURNAL_CLOSED");}
    public synchronized void close(){closed=true;}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{PreparedStatement s=c.prepareStatement(sql);s.setQueryTimeout(30);return s;}

}
