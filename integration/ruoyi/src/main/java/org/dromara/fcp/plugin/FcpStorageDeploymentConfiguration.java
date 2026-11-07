package org.dromara.fcp.plugin;

import fcp.FcpException;
import fcp.storage.StorageDeploymentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/** Optional server-only deployment identity. It is deliberately NOT a DataSource Spring bean:
 * the normal runtime DataSource must never be replaced or ambiguously autowired by deployment.
 */
@Configuration
@ConditionalOnProperty(name="fcp.runtime.enabled",havingValue="true")
public class FcpStorageDeploymentConfiguration {
    public static final class DeploymentAccess {
        private final StorageDeploymentService service;
        private DeploymentAccess(StorageDeploymentService service){this.service=service;}
        public boolean configured(){return service!=null;}
        public StorageDeploymentService require(){if(service==null)throw new FcpException("DEPLOYMENT_NOT_CONFIGURED","Storage migration is disabled; an operator must configure a separate deployment identity");return service;}
    }
    @Bean public DeploymentAccess fcpStorageDeploymentAccess(Environment env,DataSource runtime,NativePluginHost host){
        if(!Boolean.parseBoolean(env.getProperty("fcp.runtime.storage-deployment.enabled","false")))return new DeploymentAccess(null);
        String url=secret(env,"FCP_STORAGE_DEPLOYMENT_JDBC_URL"),user=secret(env,"FCP_STORAGE_DEPLOYMENT_USER"),password=secret(env,"FCP_STORAGE_DEPLOYMENT_PASSWORD");
        validateUrl(url);
        DataSource deployment=new DeploymentDataSource(url,user,password);
        try(Connection r=runtime.getConnection();Connection d=deployment.getConnection()){
            Identity runtimeIdentity=identity(r),deployIdentity=identity(d);
            if(!runtimeIdentity.database().equals(deployIdentity.database())||!runtimeIdentity.server().equals(deployIdentity.server())||runtimeIdentity.account().equals(deployIdentity.account())||accountName(runtimeIdentity.account()).equals(accountName(deployIdentity.account())))
                throw new IllegalArgumentException("Deployment must use the same database/server and a distinct database account from runtime");
        }catch(SQLException e){throw new IllegalArgumentException("Unable to verify isolated deployment database configuration");}
        return new DeploymentAccess(new StorageDeploymentService(deployment,host));
    }
    private static void validateUrl(String url){
        try{
            if(!url.startsWith("jdbc:mysql://"))throw new IllegalArgumentException();
            var parsed=java.net.URI.create(url.substring(5));
            if(parsed.getHost()==null||parsed.getRawUserInfo()!=null||parsed.getRawFragment()!=null||parsed.getRawPath()==null||!parsed.getRawPath().matches("/[A-Za-z0-9_]+"))throw new IllegalArgumentException();
            // Reject connection properties that can run SQL/create databases or embed credentials.
            Set<String> allowed=Set.of("sslmode","usessl","requiressl","verifyservercertificate","allowpublickeyretrieval","servertimezone","connectiontimezone","characterencoding","useunicode","connecttimeout","sockettimeout","tcpkeepalive");
            if(parsed.getRawQuery()!=null)for(String item:parsed.getRawQuery().split("&")){int equals=item.indexOf('=');if(equals<1)throw new IllegalArgumentException();String key=java.net.URLDecoder.decode(item.substring(0,equals),java.nio.charset.StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);if(!allowed.contains(key))throw new IllegalArgumentException();}
        }catch(RuntimeException e){throw new IllegalArgumentException("Deployment requires a single MySQL database URL with approved connection properties and separate server-only credentials");}
    }
    private static String secret(Environment env,String key){String value=env.getProperty(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Deployment enabled without complete server-only configuration");return value;}
    private record Identity(String account,String database,String server){}
    private static Identity identity(Connection c)throws SQLException{
        if(!c.getAutoCommit())throw new SQLException("Independent connection required");
        try(PreparedStatement p=c.prepareStatement("SELECT CURRENT_USER(),DATABASE(),@@server_uuid")){p.setQueryTimeout(5);try(ResultSet r=p.executeQuery()){if(!r.next()||r.getString(2)==null)throw new SQLException("Selected database required");return new Identity(r.getString(1),r.getString(2),r.getString(3));}}
    }
    private static String accountName(String account){int i=account.lastIndexOf('@');return i<0?account:account.substring(0,i);}
    /** Credentials never leave this private object and never enter HTTP models or error messages. */
    private static final class DeploymentDataSource implements DataSource {
        private final String url,user,password;
        private DeploymentDataSource(String url,String user,String password){this.url=url;this.user=user;this.password=password;}
        public Connection getConnection()throws SQLException{Properties p=new Properties();p.setProperty("user",user);p.setProperty("password",password);p.setProperty("connectTimeout","5000");p.setProperty("socketTimeout","45000");return DriverManager.getConnection(url,p);}
        public Connection getConnection(String u,String p)throws SQLException{throw new SQLException("Credential override forbidden");}
        public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter writer){throw new UnsupportedOperationException("Credential logging forbidden");}
        public void setLoginTimeout(int seconds){throw new UnsupportedOperationException();}public int getLoginTimeout(){return 5;}
        public Logger getParentLogger(){return Logger.getLogger("org.dromara.fcp.storage.deployment");}
        public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("Unwrap forbidden");}public boolean isWrapperFor(Class<?> type){return false;}
        @Override public String toString(){return "IsolatedDeploymentDataSource[redacted]";}
    }
}
