package org.dromara.fcp.plugin;

import dev.fcp.host.*;
import fcp.FcpException;
import fcp.host.HostCapabilities;
import fcp.release.control.*;
import fcp.release.version.*;
import fcp.storage.JdbcStateJournal;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.sql.*;
import java.time.Duration;
import java.util.*;

/** Opt-in control plane. Existing deployments are unchanged until explicitly configured. */
@Configuration
@ConditionalOnProperty(name="fcp.release.enabled",havingValue="true")
public class FcpSystemReleaseConfiguration {
    @Bean @ConditionalOnMissingBean(VersionProvider.class)
    public VersionProvider fcpSystemVersionProvider(DataSource source,Environment env) {
        String provider=env.getProperty("FCP_RELEASE_VERSION_PROVIDER","jdbc");
        if(provider.equals("jdbc"))return new JournalVersionProvider("fcp-jdbc",new JdbcStateJournal(source,"system_versions"));
        if(provider.equals("http"))return new HttpVersionProvider("external-http",URI.create(env.getRequiredProperty("FCP_RELEASE_VERSION_ENDPOINT")),Boolean.parseBoolean(env.getProperty("FCP_RELEASE_VERSION_WRITABLE","false")),Duration.ofSeconds(10),false);
        throw new IllegalArgumentException("Unknown system version provider; register a VersionProvider bean for a custom implementation");
    }
    @Bean public SystemVersionApplication fcpSystemVersions(NativePluginHost host,VersionProvider versions){return new SystemVersionApplication(host,versions);}
    @Bean @ConditionalOnMissingBean(DeploymentProvider.class)
    public DeploymentProvider fcpReleaseDeployment(Environment env) {
        Set<DeploymentProvider.Action> capabilities=new HashSet<>();
        for(String action:env.getRequiredProperty("FCP_RELEASE_EXECUTOR_ACTIONS").split(","))capabilities.add(DeploymentProvider.Action.valueOf(action.trim()));
        return new HttpDeploymentProvider(URI.create(env.getRequiredProperty("FCP_RELEASE_EXECUTOR_ENDPOINT")),HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build(),Duration.ofSeconds(10),capabilities,false,Boolean.parseBoolean(env.getProperty("FCP_RELEASE_EXECUTOR_SAFE_RECOVERY","false")),Boolean.parseBoolean(env.getProperty("FCP_RELEASE_EXECUTOR_FENCED_CONTAINMENT","false")));
    }
    @Bean public ReleaseController fcpSystemReleaseController(DataSource source,NativePluginHost host,VersionProvider versions,DeploymentProvider executor,Environment env) {
        String environment=env.getRequiredProperty("FCP_RELEASE_ENVIRONMENT");
        if(!environment.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))throw new IllegalArgumentException("Invalid deployment environment");
        SystemReleasePreflight preflight=new SystemReleasePreflight(versions,()->schema(source));
        return new ReleaseController(new JdbcStateJournal(source,"system_release"),executor,(ctx,permission)->{
            if(ctx.principal().kind()!=fcp.host.TrustedContext.Kind.USER)throw new FcpException("PERMISSION_DENIED","User release operator required");
            String operation=switch(permission){case "fcp:release:view"->"view";case "fcp:release:manage"->"manage";default->throw new FcpException("PERMISSION_DENIED","Unsupported release permission");};
            host.require(ctx,new HostCapabilities.Grant("control","system.release",operation,"tenant:"+ctx.tenantId()));
        },plan->{if(!environment.equals(plan.environment()))throw new IOException("RELEASE_ENVIRONMENT_NOT_CONFIGURED");preflight.verify(plan);},host.clock());
    }
    private static SystemReleasePreflight.SchemaState schema(DataSource source)throws IOException {
        // No DDL and no caller-declared schema. Deployment migration ledger is authoritative.
        try(Connection c=source.getConnection();PreparedStatement q=c.prepareStatement("SELECT version_num,state FROM fcp_migration_history ORDER BY version_num")) {
            q.setQueryTimeout(5);long version=0;boolean dirty=false;int count=0;
            try(ResultSet r=q.executeQuery()){while(r.next()){count++;version=Math.max(version,r.getLong(1));dirty|=!"APPLIED".equals(r.getString(2));}}
            if(count==0)throw new IOException("MIGRATION_LEDGER_EMPTY");
            return new SystemReleasePreflight.SchemaState(version,dirty);
        }catch(SQLException e){throw new IOException("MIGRATION_LEDGER_UNAVAILABLE",e);}
    }
}
