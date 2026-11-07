package fcp.storage;

import fcp.*;
import fcp.host.*;
import javax.sql.DataSource;
import java.sql.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Host-only JDBC implementation. Plugins receive bound sessions, never a DataSource or SQL API.
 * Each mutation commits its record, idempotency receipt and audit in one MySQL transaction.
 * HostCapabilities MUST evaluate live plugin, principal, tenant and collection data-scope grants.
 */
public final class JdbcPluginStorage {
    public record Record(String id,Map<String,Object> body,long revision,boolean deleted){public Record{body=Collections.unmodifiableMap(new LinkedHashMap<>(body));}}
    private final DataSource source;private final HostCapabilities host;private final JdbcPhysicalStorage physical;
    public JdbcPluginStorage(DataSource runtimeDml,HostCapabilities host){source=Objects.requireNonNull(runtimeDml);this.host=Objects.requireNonNull(host);physical=new JdbcPhysicalStorage(source,host);}
    /** Called only by trusted routing, never bind a pluginId supplied in business input. */
    public Session bind(String serverPluginId,TrustedContext context,String dataScope){
        Objects.requireNonNull(context);if(context.tenantId().length()>128)throw new IllegalArgumentException("Tenant length");
        if(dataScope==null || !(dataScope.equals("tenant:"+context.tenantId()) || dataScope.matches("class:[a-zA-Z0-9_.-]{1,100}")))throw new IllegalArgumentException("Invalid data scope");
        return new Session(StorageSchema.pluginIdentifier(serverPluginId),context,dataScope);
    }
    public final class Session {
        private final String plugin;private final TrustedContext context;private final String dataScope;
        private Session(String p,TrustedContext c,String scope){plugin=p;context=c;dataScope=scope;}
        private void require(String collection,String op){StorageSchema.identifier(collection);host.require(context,new HostCapabilities.Grant(plugin,"storage",op,dataScope));}
        public Optional<Record> get(String collection,String id){require(collection,"read");StorageSchema.identifier(id);if(physical.registered(plugin,collection))return physical.bind(plugin,context,dataScope).get(collection,id);try(Connection c=source.getConnection()){schema(c,collection,false);return Optional.ofNullable(read(c,collection,id,false));}catch(SQLException e){throw failure(e);}}
        /** Keyset pagination in record-id order; filters only declared scalar equality, no expressions. */
        public List<Record> list(String collection,Map<String,Object> equals,String after,int limit){
            require(collection,"read");if(limit<1||limit>100)throw new IllegalArgumentException("Page limit");if(after!=null)StorageSchema.identifier(after);if(physical.registered(plugin,collection))return physical.bind(plugin,context,dataScope).list(collection,equals,after,limit);
            try(Connection c=source.getConnection()){
                StorageSchema s=schema(c,collection,false);s.validate(equals);
                StringBuilder sql=new StringBuilder("SELECT record_id,body,revision,del FROM fcp_plugin_record WHERE tenant_id=? AND plugin_id=? AND collection_id=? AND data_scope=? AND del=0 AND record_id>? ");
                List<String> fields=new ArrayList<>(equals.keySet());Collections.sort(fields);
                for(String f:fields)sql.append("AND JSON_EXTRACT(body,?) = CAST(? AS JSON) ");sql.append("ORDER BY record_id LIMIT ?");
                try(PreparedStatement ps=statement(c,sql.toString())){int i=scope(ps,collection);ps.setString(i++,after==null?"":after);for(String f:fields){ps.setString(i++,"$.\""+f+"\"");ps.setString(i++,Json.write(equals.get(f)));}ps.setInt(i,limit);try(ResultSet r=ps.executeQuery()){List<Record> out=new ArrayList<>();while(r.next())out.add(record(r));return List.copyOf(out);}}
            }catch(SQLException e){throw failure(e);}
        }
        public Record put(String collection,String id,Map<String,Object> body,long expectedRevision,String key){return mutate(collection,id,body,expectedRevision,key,"write");}
        public Record delete(String collection,String id,long expectedRevision,String key){return mutate(collection,id,null,expectedRevision,key,"delete");}
        public Record restore(String collection,String id,long expectedRevision,String key){return mutate(collection,id,null,expectedRevision,key,"restore");}
        private Record mutate(String col,String id,Map<String,Object> supplied,long expected,String key,String op){
            require(col,op);StorageSchema.identifier(id);if(expected<0||expected==Long.MAX_VALUE||key==null||key.isBlank()||key.length()>128)throw new IllegalArgumentException("Invalid mutation");
            if(physical.registered(plugin,col)){var s=physical.bind(plugin,context,dataScope);return switch(op){case "write"->s.put(col,id,supplied,expected,key);case "delete"->s.delete(col,id,expected,key);case "restore"->s.restore(col,id,expected,key);default->throw new AssertionError();};}
            Map<String,Object> body=supplied==null?null:new TreeMap<>(supplied);
            String digest=hash(Json.write(Arrays.asList(op,col,id,expected,body)));String receipt=hash(Json.write(List.of(context.tenantId(),plugin,context.principal().kind().name(),context.principal().id(),dataScope,op,key)));
            boolean committing=false;
            try(Connection c=source.getConnection()){
                c.setAutoCommit(false);
                try{
                    StorageSchema s=schema(c,col,true);if(body!=null)s.validate(body);
                    try(PreparedStatement p=statement(c,"SELECT request_digest,result_json FROM fcp_plugin_receipt WHERE receipt_id=?")){p.setString(1,receipt);try(ResultSet r=p.executeQuery()){if(r.next()){if(!digest.equals(r.getString(1)))throw error("IDEMPOTENCY_CONFLICT");Record old=decode(r.getString(2));require(col,op);c.rollback();return old;}}}
                    Record old=read(c,col,id,true);if((old==null?0:old.revision())!=expected)throw error("VERSION_CONFLICT");
                    boolean deleted=op.equals("delete");
                    if(op.equals("write")&&old!=null&&old.deleted())throw error("RECORD_DELETED");
                    if(!op.equals("write")){if(old==null)throw error("NOT_FOUND");if(old.deleted()==deleted)throw error("VERSION_CONFLICT");body=new TreeMap<>(old.body());}
                    if(!deleted)unique(c,s,col,id,body);
                    Record result=new Record(id,body,expected+1,deleted);
                    if(old==null){try(PreparedStatement p=statement(c,"INSERT INTO fcp_plugin_record(tenant_id,plugin_id,collection_id,data_scope,record_id,body,revision,del) VALUES(?,?,?,?,?,?,1,0)")){int i=scope(p,col);p.setString(i++,id);p.setString(i,Json.write(body));p.executeUpdate();}}
                    else try(PreparedStatement p=statement(c,"UPDATE fcp_plugin_record SET body=?,revision=revision+1,del=? WHERE tenant_id=? AND plugin_id=? AND collection_id=? AND data_scope=? AND record_id=? AND revision=?")){p.setString(1,Json.write(body));p.setInt(2,deleted?1:0);p.setString(3,context.tenantId());p.setString(4,plugin);p.setString(5,col);p.setString(6,dataScope);p.setString(7,id);p.setLong(8,expected);if(p.executeUpdate()!=1)throw error("VERSION_CONFLICT");}
                    try(PreparedStatement p=statement(c,"INSERT INTO fcp_plugin_receipt(receipt_id,request_digest,result_json) VALUES(?,?,?)")){p.setString(1,receipt);p.setString(2,digest);p.setString(3,encode(result));p.executeUpdate();}
                    try(PreparedStatement p=statement(c,"INSERT INTO fcp_plugin_storage_audit(tenant_id,plugin_id,principal_id,principal_kind,request_id,collection_id,data_scope,record_id,action,revision) VALUES(?,?,?,?,?,?,?,?,?,?)")){p.setString(1,context.tenantId());p.setString(2,plugin);p.setString(3,context.principal().id());p.setString(4,context.principal().kind().name());p.setString(5,context.requestId());p.setString(6,col);p.setString(7,dataScope);p.setString(8,id);p.setString(9,op);p.setLong(10,result.revision());p.executeUpdate();}
                    require(col,op);committing=true;c.commit();return result;
                }catch(SQLException|RuntimeException e){try{c.rollback();}catch(SQLException ignored){}throw e;}
            }catch(SQLException e){if(committing)throw new FcpException("OUTCOME_UNKNOWN","Storage commit outcome unknown; retry with the same key");throw failure(e);}
        }
        private void unique(Connection c,StorageSchema s,String col,String id,Map<String,Object> body)throws SQLException{
            for(String f:s.uniqueFields())if(body.get(f)!=null)try(PreparedStatement p=statement(c,"SELECT record_id FROM fcp_plugin_record WHERE tenant_id=? AND plugin_id=? AND collection_id=? AND data_scope=? AND del=0 AND record_id<>? AND JSON_EXTRACT(body,?)=CAST(? AS JSON) LIMIT 1")){int i=scope(p,col);p.setString(i++,id);p.setString(i++,"$.\""+f+"\"");p.setString(i,Json.write(body.get(f)));try(ResultSet r=p.executeQuery()){if(r.next())throw error("UNIQUE_CONFLICT");}}
        }
        private int scope(PreparedStatement p,String col)throws SQLException{p.setString(1,context.tenantId());p.setString(2,plugin);p.setString(3,col);p.setString(4,dataScope);return 5;}
        private StorageSchema schema(Connection c,String col,boolean lock)throws SQLException{try(PreparedStatement p=statement(c,"SELECT schema_json FROM fcp_plugin_schema WHERE plugin_id=? AND collection_id=?"+(lock?" FOR UPDATE":""))){p.setString(1,plugin);p.setString(2,col);try(ResultSet r=p.executeQuery()){if(!r.next())throw error("SCHEMA_NOT_REGISTERED");return StorageSchema.parse(r.getString(1));}}}
        private Record read(Connection c,String col,String id,boolean deleted)throws SQLException{try(PreparedStatement p=statement(c,"SELECT record_id,body,revision,del FROM fcp_plugin_record WHERE tenant_id=? AND plugin_id=? AND collection_id=? AND data_scope=? AND record_id=?"+(deleted?"":" AND del=0"))){int i=scope(p,col);p.setString(i,id);try(ResultSet r=p.executeQuery()){return r.next()?record(r):null;}}}
    }
    @SuppressWarnings("unchecked") private static Record record(ResultSet r)throws SQLException{return new Record(r.getString(1),(Map<String,Object>)Json.parse(r.getString(2)),r.getLong(3),r.getInt(4)==1);}
    private static String encode(Record r){return Json.write(Map.of("id",r.id(),"body",r.body(),"revision",r.revision(),"deleted",r.deleted()));}
    @SuppressWarnings("unchecked") private static Record decode(String j){var m=(Map<String,Object>)Json.parse(j);return new Record((String)m.get("id"),(Map<String,Object>)m.get("body"),((Number)m.get("revision")).longValue(),(Boolean)m.get("deleted"));}
    static FcpException error(String code){return new FcpException(code,"Storage request rejected");}
    static FcpException failure(SQLException e){return new FcpException("STORAGE_UNAVAILABLE","Storage operation failed");}
    public static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{PreparedStatement s=c.prepareStatement(sql);s.setQueryTimeout(30);return s;}

}
