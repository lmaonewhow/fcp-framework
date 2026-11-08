import dev.fcp.host.*;
import fcp.*;
import fcp.host.*;
import fcp.plugin.PluginManifest;
import javax.sql.DataSource;
import java.sql.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;

/** SQL-binding adapter fixture, NOT a database engine and NOT native/MySQL acceptance. */
public class VersionPolicyTest {
    static int checks;
    static void check(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
    static void reject(Runnable r,String label){checks++;try{r.run();throw new AssertionError(label);}catch(FcpException|IllegalArgumentException expected){}}
    static Object zero(Class<?> c){if(c==boolean.class)return false;if(c==int.class)return 0;if(c==long.class)return 0L;return null;}
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler h){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},h);}
    static class Database {
        Map<String,Object[]> rows=new HashMap<>();int audits;boolean failRead,commitUnknown;List<String> sqls=new ArrayList<>();
        DataSource source(){return proxy(DataSource.class,(p,m,a)->m.getName().equals("getConnection")?connection():zero(m.getReturnType()));}
        Connection connection(){boolean[] auto={true};return proxy(Connection.class,(p,m,a)->switch(m.getName()){
            case "getAutoCommit"->auto[0];case "setAutoCommit"->{auto[0]=(Boolean)a[0];yield null;}
            case "prepareStatement"->statement((String)a[0]);case "commit"->{if(commitUnknown)throw new SQLException("simulated uncertain commit","08006");yield null;}
            default->zero(m.getReturnType());});}
        PreparedStatement statement(String sql){sqls.add(sql);Map<Integer,Object> args=new HashMap<>();return proxy(PreparedStatement.class,(p,m,a)->{
            if(m.getName().startsWith("set")&&!m.getName().equals("setQueryTimeout")){args.put((Integer)a[0],a[1]);return null;}
            if(m.getName().equals("executeQuery")){if(failRead)throw new SQLException("read failed");Object[] row=rows.get((String)args.get(1));int[] i={0};return proxy(ResultSet.class,(x,n,b)->switch(n.getName()){
                case "next"->row!=null&&i[0]++==0;case "getString"->(String)row[(Integer)b[0]-1];case "getLong"->((Number)row[(Integer)b[0]-1]).longValue();case "getInt"->((Number)row[(Integer)b[0]-1]).intValue();case "getTimestamp"->row[(Integer)b[0]-1];default->zero(n.getReturnType());});}
            if(m.getName().equals("executeUpdate")){
                if(sql.startsWith("INSERT INTO fcp_plugin_version_grant")){String id=(String)args.get(1);if(rows.containsKey(id))throw new SQLException("duplicate","23000");Object[] row=new Object[13];for(int i=0;i<13;i++)row[i]=args.get(i+2);rows.put(id,row);return 1;}
                if(sql.startsWith("UPDATE fcp_plugin_version_grant")){Object[] row=rows.get((String)args.get(5));if(row==null||((Number)row[10]).longValue()!=((Number)args.get(6)).longValue())return 0;row[9]=args.get(1);row[10]=args.get(2);row[11]=args.get(3);row[12]=args.get(4);return 1;}
                if(sql.startsWith("INSERT INTO fcp_runtime_audit")){audits++;return 1;}
                throw new AssertionError("Unexpected SQL");
            }
            return zero(m.getReturnType());});}
    }
    static final Instant NOW=Instant.parse("2026-11-07T00:00:00Z");
    static TrustedContext ctx(TrustedContext.Kind kind,String principal,String tenant){return new TrustedContext("r","t",tenant,new TrustedContext.Principal(kind,principal),1,Set.of("class:a"),NOW.plusSeconds(60));}
    static PluginManifest manifest(VersionPluginPolicy.Version v){var cap=new PluginManifest.Capability("echo","1.0.0","echo",false,true,1000,Map.of("type","object"),Map.of("type","object"));return new PluginManifest(v.pluginId(),v.version(),"trusted","1.0",new PluginManifest.Runtime("local","main.py",List.of()),List.of(),List.of(cap),List.of(new PluginManifest.Capability("host.storage.get","1.0.0","storage.read",false,true,1000,Map.of("type","object"),Map.of("type","object"))),Map.of(),Map.of(),List.of(),Map.of());}
    public static void main(String[] args){
        var db=new Database();boolean[] active={true},manage={true},permission={true};
        var policy=new JdbcVersionPluginPolicy(db.source(),c->new VersionPluginPolicy.Identity(active[0],permission[0]?Set.of("fcp:plugin:echo","fcp:plugin:storage.read"):Set.of(),Set.of("class:a"),3),VersionPolicyTest::manifest,(c,t)->{if(!manage[0])throw new FcpException("DENIED","management denied");},Clock.fixed(NOW,ZoneOffset.UTC));
        var v1=new VersionPluginPolicy.Version("demo","1.0.0","a".repeat(64));var v2=new VersionPluginPolicy.Version("demo","2.0.0","b".repeat(64));
        var user=ctx(TrustedContext.Kind.USER,"u","tenant-a");var task=ctx(TrustedContext.Kind.TASK,"job-a","tenant-a");var grant=new HostCapabilities.Grant("demo","echo","invoke","tenant:tenant-a");
        var key=new VersionPluginPolicy.GrantKey("tenant-a",v1,"echo","invoke","tenant:tenant-a",TrustedContext.Kind.USER,"*");
        check(!policy.authorize(user,v1,grant).allowed(),"absence denied");var approval=policy.change(user,key,true,null,0);check(approval.revision()==1&&approval.permission().equals("fcp:plugin:echo"),"permission derived from verified manifest");
        check(policy.authorize(user,v1,grant).allowed(),"approved digest admitted");check(!policy.authorize(user,v2,grant).allowed(),"different package digest cannot inherit");
        check(!policy.authorize(ctx(TrustedContext.Kind.USER,"u","tenant-b"),v1,grant).allowed(),"foreign tenant scope denied");
        permission[0]=false;check(!policy.authorize(user,v1,grant).allowed(),"fresh user revoke denies");permission[0]=true;
        active[0]=false;check(!policy.authorize(user,v1,grant).allowed(),"inactive tenant identity denied");active[0]=true;
        check(!policy.authorize(task,v1,grant).allowed(),"USER approval does not authorize TASK");
        var tk=new VersionPluginPolicy.GrantKey("tenant-a",v1,"echo","invoke","tenant:tenant-a",TrustedContext.Kind.TASK,"job-a");
        reject(()->policy.change(user,tk,true,null,0),"task expiry mandatory");policy.change(user,tk,true,NOW.plusSeconds(30),0);check(policy.authorize(task,v1,grant).allowed(),"exact approved task admitted");
        check(!policy.authorize(ctx(TrustedContext.Kind.TASK,"job-b","tenant-a"),v1,grant).allowed(),"other task denied");
        policy.change(user,key,false,null,1);check(!policy.authorize(task,v1,grant).allowed(),"base revoke wins over task approval");
        check(policy.read(user,key).revision()==2&&!policy.read(user,key).allowed(),"revocation remains stored tombstone");
        reject(()->policy.change(user,key,true,null,1),"stale revision refused");policy.change(user,key,true,null,2);
        var expired=new JdbcVersionPluginPolicy(db.source(),c->new VersionPluginPolicy.Identity(true,Set.of("fcp:plugin:echo"),Set.of(),1),VersionPolicyTest::manifest,(c,t)->{},Clock.fixed(NOW.plusSeconds(31),ZoneOffset.UTC));check(!expired.authorize(task,v1,grant).allowed(),"task expiry denies");
        manage[0]=false;reject(()->policy.read(user,key),"read management gate");reject(()->policy.change(user,key,false,null,3),"write management gate");manage[0]=true;
        reject(()->policy.change(task,key,false,null,3),"TASK cannot manage");
        var undeclared=new VersionPluginPolicy.GrantKey("tenant-a",v1,"unknown","invoke","tenant:tenant-a",TrustedContext.Kind.USER,"*");reject(()->policy.change(user,undeclared,true,null,0),"undeclared capability cannot grant");
        db.failRead=true;reject(()->policy.authorize(user,v1,grant),"SQL failure closes admission");db.failRead=false;
        db.commitUnknown=true;reject(()->policy.change(user,key,false,null,3),"unknown commit surfaced");db.commitUnknown=false;check(!policy.read(user,key).allowed(),"unknown commit can be reconciled by read");
        var storage=new VersionPluginPolicy.GrantKey("tenant-a",v1,"host.storage.get","invoke","tenant:tenant-a",TrustedContext.Kind.USER,"*");
        policy.change(user,storage,true,null,0);
        check(policy.authorize(user,v1,new HostCapabilities.Grant("demo","storage","read","tenant:tenant-a")).allowed(),"storage secondary read resolves approved get");
        check(!policy.authorize(user,v1,new HostCapabilities.Grant("demo","storage","write","tenant:tenant-a")).allowed(),"undeclared storage write denied");
        check(!policy.authorize(user,v2,new HostCapabilities.Grant("demo","storage","read","tenant:tenant-a")).allowed(),"storage approval does not inherit new digest");
        policy.change(user,storage,false,null,1);
        check(!policy.authorize(user,v1,new HostCapabilities.Grant("demo","storage","read","tenant:tenant-a")).allowed(),"storage revoke effective at secondary check");
        check(db.audits==7,"grant mutations audit");check(db.sqls.stream().noneMatch(s->s.contains("DELETE FROM")),"no hard delete SQL");
        System.out.println("PASS "+checks+" version grant SQL-binding checks; actual MySQL/RuoYi NOT_RUN");
    }
}
