import dev.fcp.school.*;
import fcp.*;
import fcp.state.PluginStateStore.*;
import javax.sql.DataSource;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;

/** Strict JDBC protocol doubles. These checks DO NOT execute SQL in a database. */
public final class JdbcProtocolRegression {
    static int count;
    static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);count++;System.out.println("PASS "+name);}
    static void deny(String code,Runnable run,String name){try{run.run();throw new AssertionError(name);}catch(FcpException e){check(e.code.equals(code),name+" -> "+e.code);}}
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler);}
    static final class Database {
        Map<Key,State> committed=new HashMap<>();List<String> audit=new ArrayList<>(),calls=new ArrayList<>();
        boolean failAudit,commitUnknown,active=true,failRead;List<String> permissions=new ArrayList<>(List.of("fcp:eval:list","fcp:eval:create"));
        Set<String> classes=new LinkedHashSet<>(List.of("class-a"));int connections;
        DataSource source(){return proxy(DataSource.class,(o,m,a)->{if(m.getName().equals("getConnection")){connections++;return connection();}throw new UnsupportedOperationException(m.getName());});}
        Connection connection(){
            class Session implements InvocationHandler {
                boolean auto=true;Map<Key,State> pending;List<String> pendingAudit;
                Map<Key,State> current(){return auto?committed:pending;}
                public Object invoke(Object o,Method method,Object[] args)throws Throwable{
                    return switch(method.getName()){
                        case "getAutoCommit" -> auto;
                        case "setAutoCommit" -> {boolean value=(Boolean)args[0];calls.add("auto:"+value);if(!value){pending=new HashMap<>(committed);pendingAudit=new ArrayList<>(audit);}auto=value;yield null;}
                        case "prepareStatement" -> statement((String)args[0],this);
                        case "commit" -> {calls.add("commit");committed=new HashMap<>(pending);audit=new ArrayList<>(pendingAudit);if(commitUnknown)throw new SQLException("Injected post-commit disconnect");yield null;}
                        case "rollback" -> {calls.add("rollback");pending=new HashMap<>(committed);pendingAudit=new ArrayList<>(audit);yield null;}
                        case "abort" -> {calls.add("abort");yield null;}
                        case "close" -> {calls.add("close");yield null;}
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                }
                PreparedStatement statement(String sql,Session session){
                    Map<Integer,Object> params=new HashMap<>();
                    return proxy(PreparedStatement.class,(o,m,a)->{
                        if(Set.of("setObject","setString","setInt","setBoolean","setLong").contains(m.getName())){params.put((Integer)a[0],a[1]);return null;}
                        if(m.getName().equals("setQueryTimeout")){checkInternal((Integer)a[0]>0,"Query timeout missing");return null;}
                        if(m.getName().equals("close"))return null;
                        if(m.getName().equals("executeUpdate")){
                            if(sql.startsWith("INSERT INTO fcp_plugin_state(")){
                                Key key=new Key((String)params.get(1),(String)params.get(2));session.current().putIfAbsent(key,new State((Boolean)params.get(3),1));return 1;
                            }
                            if(sql.startsWith("UPDATE fcp_plugin_state SET")){
                                Key key=new Key((String)params.get(4),(String)params.get(5));State old=session.current().get(key);
                                if(old==null||old.revision()!=((Number)params.get(6)).longValue())return 0;
                                session.current().put(key,new State((Boolean)params.get(1),((Number)params.get(2)).longValue()));return 1;
                            }
                            if(sql.startsWith("INSERT INTO fcp_plugin_state_audit(")){
                                if(failAudit)throw new SQLException("Injected audit failure");session.pendingAudit.add(params.toString());return 1;
                            }
                            throw new SQLException("Unrecognized update contract");
                        }
                        if(m.getName().equals("executeQuery")){
                            if(failRead)throw new SQLException("Injected read outage");
                            List<Object[]> rows=new ArrayList<>();
                            if(sql.startsWith("SELECT enabled,revision FROM fcp_plugin_state")){
                                State state=session.current().get(new Key((String)params.get(1),(String)params.get(2)));
                                if(state!=null)rows.add(new Object[]{state.enabled(),state.revision()});
                            }else if(sql.startsWith("SELECT u.user_id")){
                                checkInternal(sql.contains("u.status='0'")&&sql.contains("t.expire_time>CURRENT_TIMESTAMP")&&sql.contains("t.del_flag='0'"),"active account predicate lost");
                                if(active&&"000000".equals(params.get(1))&&"100".equals(params.get(2)))rows.add(new Object[]{"100"});
                            }else if(sql.startsWith("SELECT DISTINCT m.perms")){
                                checkInternal(sql.contains("r.tenant_id=?")&&sql.contains("r.del_flag='0'")&&sql.contains("m.status='0'")&&sql.contains("p.status='0'")&&sql.contains("FIND_IN_SET"),"role/package scope predicate lost");
                                for(String permission:permissions)rows.add(new Object[]{permission});
                            }else if(sql.startsWith("SELECT class_id FROM fcp_eval_assignment")){
                                checkInternal("tenant-000000".equals(params.get(1))&&"100".equals(params.get(2)),"class scope not bound");for(String c:classes)rows.add(new Object[]{c});
                            }else throw new SQLException("Unrecognized query contract");
                            return results(rows);
                        }
                        throw new UnsupportedOperationException(m.getName());
                    });
                }
            }
            return proxy(Connection.class,new Session());
        }
        static void checkInternal(boolean condition,String name){if(!condition)throw new AssertionError(name);}
        static ResultSet results(List<Object[]> rows){
            int[] index={-1};return proxy(ResultSet.class,(o,m,a)->{
                if(m.getName().equals("next"))return ++index[0]<rows.size();if(m.getName().equals("close"))return null;
                Object value=rows.get(index[0])[(Integer)a[0]-1];
                return switch(m.getName()){case "getString" -> value==null?null:value.toString();case "getLong" -> ((Number)value).longValue();case "getBoolean" -> (Boolean)value;default -> throw new UnsupportedOperationException(m.getName());};
            });
        }
    }
    static void state(){
        var db=new Database();var store=new JdbcPluginStateStore(db.source());var key=new Key("tenant-a","stats");
        check(store.getOrCreate(key,false).equals(new State(false,1)),"JDBC initial state uses explicit default");
        check(store.getOrCreate(key,true).equals(new State(false,1)),"subsequent default cannot overwrite state");
        check(store.compareAndSet(key,1,true,"100").equals(new State(true,2)),"JDBC CAS increments revision");
        check(db.audit.size()==1&&db.calls.contains("commit"),"state and audit committed together in protocol");
        int events=db.audit.size();deny("STALE_COMPOSITION",()->store.compareAndSet(key,1,false,"100"),"JDBC stale CAS rejected");
        check(db.audit.size()==events&&db.calls.contains("rollback"),"stale CAS leaves no audit mutation");
        db.failAudit=true;deny("STATE_UNAVAILABLE",()->store.compareAndSet(key,2,false,"100"),"audit failure rejects state mutation");
        check(db.committed.get(key).equals(new State(true,2)),"audit failure rolls state back");db.failAudit=false;
        db.calls.clear();db.commitUnknown=true;
        deny("STATE_UNAVAILABLE",()->store.compareAndSet(key,2,false,"100"),"uncertain commit does not return success");
        check(db.calls.contains("abort")&&!db.calls.contains("auto:true")&&!db.calls.contains("rollback"),"uncertain commit aborts connection without auto-commit reset");
        db.commitUnknown=false;check(store.getOrCreate(key,true).equals(new State(false,3)),"state reread resolves committed-but-unacknowledged update");
        db.failRead=true;deny("STATE_UNAVAILABLE",()->store.getOrCreate(key,true),"JDBC outage never returns enabled default");
    }
    static void authorization(){
        var db=new Database();var auth=new JdbcHostAuthorization(db.source());
        var identity=auth.resolve("000000","100");check(identity.can("create")&&identity.classes().equals(Set.of("class-a")),"fresh grants and teaching scope loaded");
        db.permissions=List.of("fcp:eval:list");check(!auth.resolve("000000","100").can("create"),"permission revoked without a Java permission cache");
        db.permissions=List.of("*:*:*","fcp:eval:list","system:user:remove");check(auth.resolve("000000","100").permissions().equals(Set.of("fcp:eval:list")),"only explicit FCP grant vocabulary accepted");
        db.classes.clear();check(auth.resolve("000000","100").classes().isEmpty(),"removed teaching assignment does not survive in memory");
        db.active=false;deny("FORBIDDEN",()->auth.resolve("000000","100"),"inactive account rejected");db.active=true;
        deny("FORBIDDEN",()->auth.resolve("000001","100"),"authenticated identity cannot cross its tenant");
        int opened=db.connections;deny("FORBIDDEN",()->auth.resolve("000000","100 OR 1=1"),"malformed native user identifier rejected");check(opened==db.connections,"malformed identity rejected before database access");
        db.failRead=true;deny("DATA_ACCESS",()->auth.resolve("000000","100"),"authorization outage fails closed");db.failRead=false;
        db.permissions=List.of("fcp:eval:list");for(int i=0;i<33;i++)db.classes.add("class-"+i);
        deny("FORBIDDEN",()->auth.resolve("000000","100"),"class cardinality bound enforced");
    }
    public static void main(String[] args){state();authorization();System.out.println("RESULT "+count+" JDBC protocol checks PASS; SQL execution is an explicit test double, NOT MySQL");}
}
