import dev.fcp.host.*;
import fcp.*;
import fcp.host.*;
import fcp.plugin.PluginManifest;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.nio.file.*;
import java.io.PrintWriter;
import java.util.logging.Logger;

/** Real MySQL smoke, allowed only against the CI-owned disposable database. No deletion. */
public class JdbcVersionPolicySmoke {
    static int checks;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    static class Source implements DataSource {
        final Properties p;Source(Properties p){this.p=p;}
        public Connection getConnection()throws SQLException{return DriverManager.getConnection(p.getProperty("jdbc.url"),p.getProperty("jdbc.user"),p.getProperty("jdbc.password"));}
        public Connection getConnection(String a,String b)throws SQLException{throw new SQLException("Not supported");}
        public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter w){}public void setLoginTimeout(int t){}public int getLoginTimeout(){return 5;}public Logger getParentLogger(){return Logger.getGlobal();}public <T>T unwrap(Class<T> c)throws SQLException{throw new SQLException("Not supported");}public boolean isWrapperFor(Class<?> c){return false;}
    }
    static PluginManifest manifest(VersionPluginPolicy.Version v){var cap=new PluginManifest.Capability("echo","1.0.0","echo",false,true,1000,Map.of("type","object"),Map.of("type","object"));return new PluginManifest(v.pluginId(),v.version(),"trusted","1.0",new PluginManifest.Runtime("local","main.py",List.of()),List.of(),List.of(cap),List.of(),Map.of(),Map.of(),List.of(),Map.of());}
    public static void main(String[] args)throws Exception {
        if(!"true".equals(System.getenv("GITHUB_ACTIONS")))throw new IllegalStateException("Disposable CI only");
        String database=System.getenv("FCP_ACCEPTANCE_DATABASE");if(database==null||!database.matches("fcp_ci_[a-f0-9]{12}"))throw new IllegalStateException("Disposable database scope required");
        if(args.length!=1)throw new IllegalArgumentException("Private properties file required");
        Path path=Path.of(args[0]);if(!Files.isRegularFile(path)||Files.isSymbolicLink(path))throw new IllegalArgumentException("Private properties required");
        if(Files.getPosixFilePermissions(path).stream().anyMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_")))throw new IllegalArgumentException("Private properties must be owner only");
        Properties props=new Properties();try(var in=Files.newInputStream(path)){props.load(in);}
        if(!props.getProperty("jdbc.url","").startsWith("jdbc:mysql://127.0.0.1:33306/"+database+"?"))throw new IllegalArgumentException("Wrong disposable database");
        var source=new Source(props);try(Connection c=source.getConnection()){check(c.getMetaData().getDatabaseProductName().equals("MySQL"),"Actual MySQL required");}
        Clock clock=Clock.systemUTC();String tenant="policy-ci-"+UUID.randomUUID().toString().substring(0,8);
        var user=new TrustedContext("ci-policy","ci-policy",tenant,new TrustedContext.Principal(TrustedContext.Kind.USER,"ci-operator"),1,Set.of(),clock.instant().plusSeconds(120));
        var task=new TrustedContext("ci-task","ci-task",tenant,new TrustedContext.Principal(TrustedContext.Kind.TASK,"ci-job"),1,Set.of(),clock.instant().plusSeconds(120));
        var policy=new JdbcVersionPluginPolicy(source,c->new VersionPluginPolicy.Identity(true,Set.of("fcp:plugin:echo"),Set.of(),1),JdbcVersionPolicySmoke::manifest,(c,t)->{if(!c.principal().id().equals("ci-operator")||!t.equals(tenant))throw new FcpException("DENIED","CI identity");},clock);
        var version=new VersionPluginPolicy.Version("ci.policy","1.0.0","a".repeat(64));var other=new VersionPluginPolicy.Version("ci.policy","2.0.0","b".repeat(64));
        var key=new VersionPluginPolicy.GrantKey(tenant,version,"echo","invoke","tenant:"+tenant,TrustedContext.Kind.USER,"*");
        var grant=new HostCapabilities.Grant("ci.policy","echo","invoke","tenant:"+tenant);
        check(!policy.authorize(user,version,grant).allowed(),"No default grant");policy.change(user,key,true,null,0);
        check(policy.authorize(user,version,grant).allowed(),"Exact digest USER approval");check(!policy.authorize(user,other,grant).allowed(),"Other digest denied");
        var tk=new VersionPluginPolicy.GrantKey(tenant,version,"echo","invoke","tenant:"+tenant,TrustedContext.Kind.TASK,"ci-job");policy.change(user,tk,true,clock.instant().plusSeconds(60),0);
        check(policy.authorize(task,version,grant).allowed(),"Exact TASK approval");policy.change(user,key,false,null,1);
        check(!policy.authorize(task,version,grant).allowed(),"Platform revocation stops TASK");check(!policy.read(user,key).allowed()&&policy.read(user,key).revision()==2,"Tombstone retained");
        try{policy.change(user,key,true,null,1);throw new AssertionError("Stale CAS accepted");}catch(FcpException e){check(e.code.equals("VERSION_GRANT_CONFLICT"),"Stale CAS refused");}
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM fcp_runtime_audit WHERE tenant_id=? AND action LIKE 'version.grant.%'")){p.setString(1,tenant);try(ResultSet r=p.executeQuery()){r.next();check(r.getLong(1)==3,"Audit persisted with grants");}}
        System.out.println("PASS "+checks+" actual MySQL version-grant checks; native identity/UI NOT_RUN");
    }
}
