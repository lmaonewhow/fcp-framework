package fcp.storage;

import fcp.*;
import fcp.host.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.*;
import static fcp.storage.JdbcPluginStorage.*;

/** Scoped physical-table adapter. All identifiers come from validated deployed metadata; values
 * are bound parameters. Mutations/uniqueness/receipt/audit share one transaction and metadata lock.
 * PUT merges declared supplied fields on update, preserving newer additive fields for rollback.
 */
public final class JdbcPhysicalStorage {
    private final DataSource runtime;private final HostCapabilities host;
    public JdbcPhysicalStorage(DataSource runtime,HostCapabilities host){this.runtime=Objects.requireNonNull(runtime);this.host=Objects.requireNonNull(host);}
    boolean registered(String plugin,String collection){try(Connection c=runtime.getConnection();PreparedStatement p=statement(c,"SELECT table_name FROM fcp_plugin_physical_schema WHERE plugin_id=? AND structure_id=?")){p.setString(1,plugin);p.setString(2,collection);try(ResultSet r=p.executeQuery()){return r.next();}}catch(SQLException e){throw failure(e);}}
    public Session bind(String plugin,TrustedContext ctx,String scope){StorageSchema.pluginIdentifier(plugin);Objects.requireNonNull(ctx);if(ctx.tenantId().length()>128||scope==null||!(scope.equals("tenant:"+ctx.tenantId())||scope.matches("class:[a-zA-Z0-9_.-]{1,100}")))throw new IllegalArgumentException("Invalid scope");return new Session(plugin,ctx,scope);}
    private record Shape(String table,StorageDeclaration.Table descriptor){}
    public final class Session {
        private final String plugin,scope;private final TrustedContext ctx;
        private Session(String plugin,TrustedContext ctx,String scope){this.plugin=plugin;this.ctx=ctx;this.scope=scope;}
        private void require(String collection,String operation){StorageSchema.identifier(collection);host.require(ctx,new HostCapabilities.Grant(plugin,"storage",operation,scope));}
        public Optional<JdbcPluginStorage.Record> get(String collection,String id){require(collection,"read");StorageSchema.identifier(id);try(Connection c=runtime.getConnection()){Shape shape=shape(c,collection,false);return Optional.ofNullable(read(c,shape,id,false));}catch(SQLException e){throw failure(e);}}
        public List<JdbcPluginStorage.Record> list(String collection,Map<String,Object> equals,String after,int limit){
            require(collection,"read");if(limit<1||limit>100)throw new IllegalArgumentException("Limit");if(after!=null)StorageSchema.identifier(after);
            try(Connection c=runtime.getConnection()){
                Shape shape=shape(c,collection,false);Map<String,Object> filters=shape.descriptor().normalize(equals,true);
                StringBuilder sql=new StringBuilder(select(shape)+" WHERE tenant_id=? AND data_scope=? AND del=0 AND record_id>?");
                for(String f:filters.keySet())sql.append(" AND ").append(quote(f)).append(filters.get(f)==null?" IS NULL":shape.descriptor().fields().get(f).type()==PhysicalSchemaMigrator.Type.JSON?"=CAST(? AS JSON)":"=?");sql.append(" ORDER BY record_id LIMIT ?");
                try(PreparedStatement p=statement(c,sql.toString())){int i=scope(p,1);p.setString(i++,after==null?"":after);for(String f:filters.keySet())if(filters.get(f)!=null)bindValue(p,i++,shape.descriptor().fields().get(f),filters.get(f));p.setInt(i,limit);try(ResultSet r=p.executeQuery()){List<JdbcPluginStorage.Record> records=new ArrayList<>();while(r.next())records.add(record(r,shape));return List.copyOf(records);}}
            }catch(SQLException e){throw failure(e);}
        }
        public JdbcPluginStorage.Record put(String col,String id,Map<String,Object> body,long expected,String key){return mutate(col,id,body,expected,key,"write");}
        public JdbcPluginStorage.Record delete(String col,String id,long expected,String key){return mutate(col,id,null,expected,key,"delete");}
        public JdbcPluginStorage.Record restore(String col,String id,long expected,String key){return mutate(col,id,null,expected,key,"restore");}
        private JdbcPluginStorage.Record mutate(String col,String id,Map<String,Object> supplied,long expected,String key,String action){
            require(col,action);StorageSchema.identifier(id);if(expected<0||expected==Long.MAX_VALUE||key==null||key.isBlank()||key.length()>128)throw new IllegalArgumentException("Mutation");
            String digest=hash(StorageDeclaration.canonical(Arrays.asList("physical",action,col,id,expected,supplied)));String receipt=hash(Json.write(List.of("physical",ctx.tenantId(),plugin,ctx.principal().kind().name(),ctx.principal().id(),scope,action,key)));boolean committing=false;
            try(Connection c=runtime.getConnection()){
                c.setAutoCommit(false);
                try{
                    Shape shape=shape(c,col,true);
                    try(PreparedStatement p=statement(c,"SELECT request_digest,result_json FROM fcp_plugin_receipt WHERE receipt_id=?")){p.setString(1,receipt);try(ResultSet r=p.executeQuery()){if(r.next()){if(!digest.equals(r.getString(1)))throw error("IDEMPOTENCY_CONFLICT");var saved=StorageDeclaration.object(Json.parse(r.getString(2)));var result=new JdbcPluginStorage.Record((String)saved.get("id"),StorageDeclaration.object(saved.get("body")),StorageDeclaration.integer(saved.get("revision")),(Boolean)saved.get("deleted"));require(col,action);c.rollback();return result;}}}
                    var old=read(c,shape,id,true);if((old==null?0:old.revision())!=expected)throw error("VERSION_CONFLICT");boolean deleted=action.equals("delete");Map<String,Object> body;
                    if(action.equals("write")){if(old!=null&&old.deleted())throw error("RECORD_DELETED");if(supplied==null)throw new IllegalArgumentException("Body required");Map<String,Object> merged=new TreeMap<>();if(old!=null)merged.putAll(old.body());merged.putAll(supplied);body=shape.descriptor().normalize(merged,false);}
                    else{if(old==null)throw error("NOT_FOUND");if(old.deleted()==deleted)throw error("VERSION_CONFLICT");body=old.body();}
                    if(!deleted)unique(c,shape,id,body);
                    List<String> fields=new ArrayList<>(shape.descriptor().fields().keySet());
                    if(old==null){String columns=String.join(",",fields.stream().map(JdbcPhysicalStorage::quote).toList());String marks=String.join(",",fields.stream().map(f->shape.descriptor().fields().get(f).type()==PhysicalSchemaMigrator.Type.JSON?"CAST(? AS JSON)":"?").toList());
                        try(PreparedStatement p=statement(c,"INSERT INTO "+quote(shape.table())+" (tenant_id,data_scope,record_id,revision,del,"+columns+") VALUES(?,?,?,1,0,"+marks+")")){int i=scope(p,1);p.setString(i++,id);for(String f:fields)bindValue(p,i++,shape.descriptor().fields().get(f),body.get(f));p.executeUpdate();}}
                    else{String columns=String.join(",",fields.stream().map(f->quote(f)+"="+(shape.descriptor().fields().get(f).type()==PhysicalSchemaMigrator.Type.JSON?"CAST(? AS JSON)":"?")).toList());
                        try(PreparedStatement p=statement(c,"UPDATE "+quote(shape.table())+" SET "+columns+",revision=revision+1,del=? WHERE tenant_id=? AND data_scope=? AND record_id=? AND revision=?")){int i=1;for(String f:fields)bindValue(p,i++,shape.descriptor().fields().get(f),body.get(f));p.setInt(i++,deleted?1:0);i=scope(p,i);p.setString(i++,id);p.setLong(i,expected);if(p.executeUpdate()!=1)throw error("VERSION_CONFLICT");}}
                    var result=new JdbcPluginStorage.Record(id,body,expected+1,deleted);
                    try(PreparedStatement p=statement(c,"INSERT INTO fcp_plugin_receipt(receipt_id,request_digest,result_json) VALUES(?,?,?)")){p.setString(1,receipt);p.setString(2,digest);p.setString(3,Json.write(StorageCapabilities.json(result)));p.executeUpdate();}
                    try(PreparedStatement p=statement(c,"INSERT INTO fcp_plugin_storage_audit(tenant_id,plugin_id,principal_id,principal_kind,request_id,collection_id,data_scope,record_id,action,revision) VALUES(?,?,?,?,?,?,?,?,?,?)")){p.setString(1,ctx.tenantId());p.setString(2,plugin);p.setString(3,ctx.principal().id());p.setString(4,ctx.principal().kind().name());p.setString(5,ctx.requestId());p.setString(6,col);p.setString(7,scope);p.setString(8,id);p.setString(9,"physical."+action);p.setLong(10,result.revision());p.executeUpdate();}
                    require(col,action);committing=true;c.commit();return result;
                }catch(SQLException|RuntimeException e){try{c.rollback();}catch(SQLException ignored){}throw e;}
            }catch(SQLException e){if(committing)throw new FcpException("OUTCOME_UNKNOWN","Storage commit outcome unknown; retry the same key");throw failure(e);}
        }
        private void unique(Connection c,Shape shape,String id,Map<String,Object> body)throws SQLException{
            for(var index:shape.descriptor().indexes())if(index.unique()&&index.fields().stream().allMatch(f->body.get(f)!=null)){
                String predicate=String.join(" AND ",index.fields().stream().map(f->quote(f)+"=?").toList());
                try(PreparedStatement p=statement(c,"SELECT record_id FROM "+quote(shape.table())+" WHERE tenant_id=? AND data_scope=? AND del=0 AND record_id<>? AND "+predicate+" LIMIT 1")){int i=scope(p,1);p.setString(i++,id);for(String f:index.fields())bindValue(p,i++,shape.descriptor().fields().get(f),body.get(f));try(ResultSet r=p.executeQuery()){if(r.next())throw error("UNIQUE_CONFLICT");}}
            }
        }
        private int scope(PreparedStatement p,int i)throws SQLException{p.setString(i++,ctx.tenantId());p.setString(i++,scope);return i;}
        private Shape shape(Connection c,String col,boolean lock)throws SQLException{
            try(PreparedStatement p=statement(c,"SELECT table_name,schema_json,schema_version FROM fcp_plugin_physical_schema WHERE plugin_id=? AND structure_id=?"+(lock?" FOR UPDATE":""))){p.setString(1,plugin);p.setString(2,col);try(ResultSet r=p.executeQuery()){if(!r.next()||r.getString(2)==null||r.getLong(3)<1)throw error("STORAGE_NOT_DEPLOYED");String name=r.getString(1);String expected=PhysicalSchemaMigrator.plan(plugin,col,List.of(new PhysicalSchemaMigrator.Step(1,PhysicalSchemaMigrator.Kind.CREATE,null,null))).table();if(!name.equals(expected))throw error("OWNERSHIP_CONFLICT");var descriptor=StorageDeclaration.parseTable(StorageDeclaration.object(Json.parse(r.getString(2))));if(!descriptor.name().equals(col))throw error("SCHEMA_METADATA_DRIFT");return new Shape(name,descriptor);}}
        }
        private JdbcPluginStorage.Record read(Connection c,Shape s,String id,boolean deleted)throws SQLException{try(PreparedStatement p=statement(c,select(s)+" WHERE tenant_id=? AND data_scope=? AND record_id=?"+(deleted?"":" AND del=0"))){int i=scope(p,1);p.setString(i,id);try(ResultSet r=p.executeQuery()){return r.next()?record(r,s):null;}}}
    }
    private static String select(Shape s){return "SELECT record_id,revision,del,"+String.join(",",s.descriptor().fields().keySet().stream().map(JdbcPhysicalStorage::quote).toList())+" FROM "+quote(s.table());}
    private static JdbcPluginStorage.Record record(ResultSet r,Shape shape)throws SQLException{
        Map<String,Object> body=new TreeMap<>();int i=4;for(var f:shape.descriptor().fields().values()){Object v=switch(f.type()){
            case TEXT -> r.getString(i);case INTEGER -> {long n=r.getLong(i);yield r.wasNull()?null:n;}
            case DECIMAL -> r.getBigDecimal(i);case BOOLEAN -> {boolean b=r.getBoolean(i);yield r.wasNull()?null:b;}
            case TIMESTAMP -> {String s=r.getString(i);yield s==null?null:LocalDateTime.parse(s.replace(' ','T')).toString();}
            case JSON -> {String s=r.getString(i);yield s==null?null:Json.parse(s);}
        };body.put(f.name(),v);i++;}return new JdbcPluginStorage.Record(r.getString(1),body,r.getLong(2),r.getInt(3)==1);
    }
    private static void bindValue(PreparedStatement p,int i,StorageDeclaration.Field f,Object v)throws SQLException{
        if(v==null){p.setNull(i,switch(f.type()){case TEXT,JSON->Types.VARCHAR;case INTEGER->Types.BIGINT;case DECIMAL->Types.DECIMAL;case BOOLEAN->Types.TINYINT;case TIMESTAMP->Types.TIMESTAMP;});return;}
        switch(f.type()){case TEXT->p.setString(i,(String)v);case INTEGER->p.setLong(i,((Number)v).longValue());case DECIMAL->p.setBigDecimal(i,new BigDecimal(v.toString()));case BOOLEAN->p.setBoolean(i,(Boolean)v);case TIMESTAMP->p.setTimestamp(i,Timestamp.valueOf(LocalDateTime.parse((String)v)));case JSON->p.setString(i,Json.write(v));}
    }
    private static String quote(String validated){if(!validated.matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("Unvalidated SQL identifier");return "`"+validated+"`";}
    private static PreparedStatement statement(Connection c,String sql)throws SQLException{return StorageDeploymentService.statement(c,sql);}
}
