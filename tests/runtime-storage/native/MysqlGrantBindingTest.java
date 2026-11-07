import dev.fcp.host.JdbcPluginPolicy;
import fcp.*;
import fcp.host.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Actual MySQL policy query regression, not a fake policy. Requires the dedicated storage test
 * database before a native host/lifecycle has created journal_id='plugins'. No account creation,
 * cleanup or overwriting another lifecycle journal. Runtime identity remains DML-only.
 */
public final class MysqlGrantBindingTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static TrustedContext context(String tenant,TrustedContext.Kind kind,String principal){return new TrustedContext("grant-test","trace",tenant,new TrustedContext.Principal(kind,principal),0,Set.of("class:1"),Instant.now().plusSeconds(300));}
    public static void main(String[] args)throws Exception{
        if(System.getenv("FCP_STORAGE_TEST_URL")==null){System.out.println("BLOCKED: actual MySQL grant-binding test has no dedicated database configuration");System.exit(2);}
        var runtime=new MysqlStorageTest.Source(System.getenv("FCP_STORAGE_TEST_USER"),System.getenv("FCP_STORAGE_TEST_PASSWORD"));
        String plugin="grant.test."+UUID.randomUUID().toString().replace("-",""),one="a".repeat(64),two="b".repeat(64);
        try(Connection c=runtime.getConnection()){
            try(PreparedStatement q=c.prepareStatement("SELECT journal_id FROM fcp_runtime_state WHERE journal_id='plugins'");ResultSet r=q.executeQuery()){if(r.next()){System.out.println("BLOCKED: grant regression requires a fresh dedicated lifecycle journal; existing state was not changed");System.exit(2);}}
            try(PreparedStatement q=c.prepareStatement("INSERT INTO fcp_runtime_state(journal_id,revision,state_json) VALUES('plugins',1,?)")){q.setString(1,Json.write(Map.of("revision",0,"installs",Map.of(plugin,Map.of("digest",one,"version","1.0.0","state","INSTALLED")))));q.executeUpdate();}
        }
        var policy=new JdbcPluginPolicy(runtime);var user=context("grant-t1",TrustedContext.Kind.USER,"u1");var task=context("grant-t1",TrustedContext.Kind.TASK,"task1");
        var grant=new HostCapabilities.Grant(plugin,"storage","read","class:1");check(!policy.permission(user,grant).granted(),"Absent platform grant allowed");
        try(Connection c=runtime.getConnection();PreparedStatement q=c.prepareStatement("INSERT INTO fcp_runtime_grant(tenant_id,plugin_id,capability_id,operation,permission,data_scope,package_digest,revision,del) VALUES(?,?,'storage','read','fcp:plugin:storage.read','class:1',?,1,0)")){q.setString(1,user.tenantId());q.setString(2,plugin);q.setString(3,one);q.executeUpdate();}
        check(policy.permission(user,grant).granted(),"Exact installed package grant denied");check(!policy.permission(task,grant).granted(),"Task inherited user platform grant without task grant");
        check(!policy.permission(context("grant-t2",TrustedContext.Kind.USER,"u1"),grant).granted(),"Cross-tenant grant accepted");
        check(!policy.permission(user,new HostCapabilities.Grant(plugin,"storage","read","class:2")).granted(),"Cross-scope grant accepted");
        try(Connection c=runtime.getConnection();PreparedStatement q=c.prepareStatement("INSERT INTO fcp_runtime_task_grant(tenant_id,task_id,plugin_id,capability_id,operation,data_scope,revision,del,expires_at) VALUES(?,'task1',?,'storage','read','class:1',1,0,DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 HOUR))")){q.setString(1,user.tenantId());q.setString(2,plugin);q.executeUpdate();}
        check(policy.permission(task,grant).granted(),"Explicit task grant denied");
        try(Connection c=runtime.getConnection();PreparedStatement q=c.prepareStatement("UPDATE fcp_runtime_state SET revision=revision+1,state_json=JSON_SET(state_json,?,?) WHERE journal_id='plugins' AND revision=1")){q.setString(1,"$.installs."+Json.write(plugin)+".digest");q.setString(2,two);check(q.executeUpdate()==1,"Fixture lifecycle CAS conflict");}
        check(!policy.permission(user,grant).granted(),"Upgraded package inherited old user grant");check(!policy.permission(task,grant).granted(),"Upgraded package inherited old task grant");
        try(Connection c=runtime.getConnection();PreparedStatement q=c.prepareStatement("UPDATE fcp_runtime_grant SET package_digest=?,revision=revision+1 WHERE tenant_id=? AND plugin_id=?")){q.setString(1,two);q.setString(2,user.tenantId());q.setString(3,plugin);q.executeUpdate();}
        check(policy.permission(user,grant).granted()&&policy.permission(task,grant).granted(),"Reviewed new package grants denied");
        try(Connection c=runtime.getConnection();PreparedStatement q=c.prepareStatement("UPDATE fcp_runtime_task_grant SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE tenant_id=? AND plugin_id=?")){q.setString(1,user.tenantId());q.setString(2,plugin);q.executeUpdate();}
        check(!policy.permission(task,grant).granted(),"Expired task grant accepted");check(policy.permission(user,grant).granted(),"Expired task affected independent user grant");
        try(Connection c=runtime.getConnection();PreparedStatement q=c.prepareStatement("UPDATE fcp_runtime_grant SET del=1,revision=revision+1 WHERE tenant_id=? AND plugin_id=?")){q.setString(1,user.tenantId());q.setString(2,plugin);q.executeUpdate();}
        check(!policy.permission(user,grant).granted()&&!policy.permission(task,grant).granted(),"Revoked grant accepted");
        System.out.println("PASS actual MySQL package-bound platform/task grants, upgrade revocation, expiry and tenant/scope checks");
    }
}
