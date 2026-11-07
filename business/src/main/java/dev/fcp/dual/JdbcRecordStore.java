package dev.fcp.dual;

import fcp.*;
import java.sql.*;
import javax.sql.DataSource;
import java.util.*;
import static fcp.Schema.*;

/** MySQL 8 adapter. Each write owns a short local transaction on a dedicated connection.
 * No ambient Spring transaction enlistment is claimed. The host supplies its DataSource.
 */
public final class JdbcRecordStore implements RecordStore {
    private static final System.Logger LOG = System.getLogger(JdbcRecordStore.class.getName());
    private static final int QUERY_TIMEOUT_SECONDS = 15;
    private final DataSource dataSource;
    public JdbcRecordStore(DataSource dataSource){this.dataSource=Objects.requireNonNull(dataSource);}
    private static FcpException storage(SQLException e){return new FcpException("STORAGE","Database operation failed; consult server logs",e);}
    private static void args(PreparedStatement p,Object... args)throws SQLException {
        p.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);
    }
    private Map<String,Object> row(ResultSet rs)throws SQLException {
        return Json.obj("scope",rs.getString("scope_id"),"kind",rs.getString("kind"),"id",rs.getString("record_id"),"version",rs.getLong("version"),"body",Json.parse(rs.getString("body_json")));
    }
    public Map<String,Object> get(String scope,String kind,String id){
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement("SELECT * FROM fcp_dual_record WHERE scope_id=? AND kind=? AND record_id=?")){
            args(p,scope,kind,id);try(ResultSet rs=p.executeQuery()){if(!rs.next())throw new FcpException("NOT_FOUND","Record missing in current scope");return row(rs);}
        }catch(SQLException e){throw storage(e);}
    }
    public List<Object> list(String scope,String kind){
        List<Object> rows=new ArrayList<>();
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement("SELECT * FROM fcp_dual_record WHERE scope_id=? AND kind=? ORDER BY created_at DESC,record_id LIMIT 10001")){
            args(p,scope,kind);try(ResultSet rs=p.executeQuery()){while(rs.next())rows.add(row(rs));}
            if(rows.size()>10000)throw new FcpException("RESULT_LIMIT","More than 10000 records; use a paginated host provider");
            return rows;
        }catch(SQLException e){throw storage(e);}
    }
    @Override public List<Object> page(String scope,String kind,Set<String> classes,boolean allClasses,String afterId,int limit){
        if(limit<1||limit>1001||classes.size()>32)throw new IllegalArgumentException("Invalid page bounds");
        if(!allClasses&&classes.isEmpty())return List.of();
        List<String> allowed=new ArrayList<>(classes);Collections.sort(allowed);
        String filter=allClasses?"":" AND class_id IN ("+String.join(",",Collections.nCopies(allowed.size(),"?"))+")";
        String sql="SELECT scope_id,kind,record_id,version,body_json FROM fcp_dual_record WHERE scope_id=? AND kind=? AND record_id>?"+filter+" ORDER BY record_id LIMIT ?";
        List<Object> values=new ArrayList<>(List.of(scope,kind,afterId));if(!allClasses)values.addAll(allowed);values.add(limit);
        List<Object> result=new ArrayList<>();
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(sql)){
            args(p,values.toArray());p.setFetchSize(Math.min(100,limit));
            try(ResultSet rs=p.executeQuery()){while(rs.next())result.add(row(rs));}return result;
        }catch(SQLException error){throw storage(error);}
    }
    public List<Object> history(String scope,String kind,String id){
        get(scope,kind,id);List<Object> events=new ArrayList<>();
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement("SELECT event_json FROM fcp_dual_audit WHERE scope_id=? AND kind=? AND record_id=? ORDER BY audit_id LIMIT 10001")){
            args(p,scope,kind,id);try(ResultSet rs=p.executeQuery()){while(rs.next())events.add(Json.parse(rs.getString(1)));}
            if(events.size()>10000)throw new FcpException("RESULT_LIMIT","More than 10000 audit entries; use a paginated host provider");
            return events;
        }catch(SQLException e){throw storage(e);}
    }
    private Map<String,Object> replay(Connection c,String scope,String subject,String key,String fingerprint)throws SQLException{
        try(PreparedStatement p=c.prepareStatement("SELECT fingerprint,result_json FROM fcp_dual_request WHERE scope_id=? AND subject_id=? AND request_key=?")){
            args(p,scope,subject,key);try(ResultSet rs=p.executeQuery()){
                if(!rs.next())return null;
                if(!fingerprint.equals(rs.getString(1)))throw new FcpException("IDEMPOTENCY_CONFLICT","Request key has another payload");
                String result=rs.getString(2);if(result==null)throw new FcpException("BUSY","Request is not yet committed");return map(Json.parse(result));
            }
        }
    }
    public Map<String,Object> replay(String scope,String subject,String key,String fingerprint){
        try(Connection c=dataSource.getConnection()){return replay(c,scope,subject,key,fingerprint);}catch(SQLException e){throw storage(e);}
    }
    private static boolean rollback(Connection c,Throwable primary) {
        try {c.rollback();return true;}
        catch(SQLException failure){primary.addSuppressed(failure);invalidate(c,primary);return false;}
    }
    private static void invalidate(Connection c,Throwable primary) {
        // Never turn auto-commit on when the outcome is unknown: doing so may COMMIT.
        try {c.abort(Runnable::run);}
        catch(SQLException | RuntimeException failure){primary.addSuppressed(failure);LOG.log(System.Logger.Level.ERROR,"Connection abort failed; no auto-commit reset attempted",primary);}
    }
    public Map<String,Object> commit(String scope,String kind,String subject,String requestKey,String fingerprint,String id,long expectedVersion,Map<String,Object> body,Map<String,Object> audit){
        final Connection c;
        try {c=dataSource.getConnection();}catch(SQLException e){throw storage(e);}
        boolean transactionStarted=false, settled=false, invalid=false, commitAttempted=false;
        try {
            if(!c.getAutoCommit())throw new FcpException("TRANSACTION_MODE","Dedicated auto-commit connection required; ambient transactions are unsupported");
            c.setAutoCommit(false);transactionStarted=true;
            Map<String,Object> previous=replay(c,scope,subject,requestKey,fingerprint);
            if(previous!=null){c.rollback();settled=true;return previous;}
            try(PreparedStatement p=c.prepareStatement("INSERT INTO fcp_dual_request(scope_id,subject_id,request_key,fingerprint) VALUES(?,?,?,?)")){
                args(p,scope,subject,requestKey,fingerprint);p.executeUpdate();
            }
            if(expectedVersion==0){
                try(PreparedStatement p=c.prepareStatement("INSERT INTO fcp_dual_record(scope_id,kind,record_id,version,body_json) VALUES(?,?,?,1,?)")){
                    args(p,scope,kind,id,Json.write(body));p.executeUpdate();
                }
            }else{
                try(PreparedStatement p=c.prepareStatement("UPDATE fcp_dual_record SET body_json=?,version=version+1 WHERE scope_id=? AND kind=? AND record_id=? AND version=?")){
                    args(p,Json.write(body),scope,kind,id,expectedVersion);if(p.executeUpdate()!=1)throw new FcpException("RECORD_CONFLICT","Record changed; refresh");
                }
            }
            Map<String,Object> result=Json.obj("scope",scope,"kind",kind,"id",id,"version",expectedVersion+1,"body",body);
            Map<String,Object> event=new LinkedHashMap<>(audit);
            var trace=fcp.observability.TraceContext.current();if(trace!=null){event.put("traceId",trace.traceId());event.put("requestId",trace.requestId());}
            event.putAll(Json.obj("scope",scope,"kind",kind,"id",id,"subject",subject,"requestKey",requestKey,"version",expectedVersion+1));
            try(PreparedStatement p=c.prepareStatement("INSERT INTO fcp_dual_audit(scope_id,kind,record_id,event_json) VALUES(?,?,?,?)")){
                args(p,scope,kind,id,Json.write(event));p.executeUpdate();
            }
            try(PreparedStatement p=c.prepareStatement("UPDATE fcp_dual_request SET result_json=? WHERE scope_id=? AND subject_id=? AND request_key=?")){
                args(p,Json.write(result),scope,subject,requestKey);if(p.executeUpdate()!=1)throw new SQLException("Request reservation missing");
            }
            commitAttempted=true;
            c.commit();settled=true;
            return result;
        }catch(SQLException failure){
            if(commitAttempted){
                invalid=true;invalidate(c,failure);
                throw new FcpException("COMMIT_UNKNOWN","Commit acknowledgement unavailable; retry the SAME request key and payload",failure);
            }
            if(transactionStarted){settled=rollback(c,failure);invalid=!settled;}
            if(!invalid && transactionStarted && (failure.getErrorCode()==1062 || "23505".equals(failure.getSQLState()))){
                try {
                    // Reuse the same connection after a confirmed rollback; safe with a one-slot pool.
                    c.setAutoCommit(true);transactionStarted=false;
                    Map<String,Object> prior=replay(c,scope,subject,requestKey,fingerprint);
                    if(prior!=null)return prior;
                    throw new FcpException("RECORD_CONFLICT","A unique record already exists",failure);
                }catch(SQLException retryFailure){failure.addSuppressed(retryFailure);throw storage(failure);}
            }
            throw storage(failure);
        }catch(RuntimeException | Error failure){
            if(transactionStarted && !settled){settled=rollback(c,failure);invalid=!settled;}
            throw failure;
        }finally{
            if(transactionStarted && settled && !invalid){
                try{c.setAutoCommit(true);}
                catch(SQLException cleanup){invalidate(c,cleanup);LOG.log(System.Logger.Level.WARNING,"Committed/rolled-back outcome preserved; connection reset failed",cleanup);}
            }
            // Cleanup errors after a confirmed commit must not masquerade as a failed write.
            try{c.close();}catch(SQLException cleanup){LOG.log(System.Logger.Level.WARNING,"Connection close failed",cleanup);}
        }
    }
}
