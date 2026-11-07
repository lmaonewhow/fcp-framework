import org.dromara.fcp.plugin.*;
import fcp.FcpException;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.*;

/** Unit-only server configuration boundary checks against real Spring classes. No native login,
 * HTTP server, MySQL connection or deployment is simulated as acceptance.
 */
public final class NativeStorageDeploymentBoundaryTest {
    static final class TestEnvironment extends StandardEnvironment {
        private final Map<String,Object> values=new HashMap<>();
        TestEnvironment(){for(var source:getPropertySources())getPropertySources().remove(source.getName());getPropertySources().addFirst(new MapPropertySource("fixture",values));}
        TestEnvironment withProperty(String key,String value){values.put(key,value);return this;}
    }
    static void rejects(Runnable action){try{action.run();throw new AssertionError("Unexpected acceptance");}catch(IllegalArgumentException expected){}}
    public static void main(String[] args)throws Exception{
        var configuration=new FcpStorageDeploymentConfiguration();
        DataSource runtime=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(p,m,a)->{throw new AssertionError("Unconfigured deployment touched runtime connection");});
        var absent=configuration.fcpStorageDeploymentAccess(new TestEnvironment(),runtime,null);
        if(absent.configured())throw new AssertionError("Deployment enabled by default");
        try{absent.require();throw new AssertionError("Unconfigured deployment allowed");}catch(FcpException e){if(!"DEPLOYMENT_NOT_CONFIGURED".equals(e.code))throw e;}
        var incomplete=new TestEnvironment().withProperty("fcp.runtime.storage-deployment.enabled","true");
        rejects(()->configuration.fcpStorageDeploymentAccess(incomplete,runtime,null));
        var credentialUrl=new TestEnvironment().withProperty("fcp.runtime.storage-deployment.enabled","true").withProperty("FCP_STORAGE_DEPLOYMENT_JDBC_URL","jdbc:mysql://127.0.0.1/test?password=forbidden").withProperty("FCP_STORAGE_DEPLOYMENT_USER","fixture").withProperty("FCP_STORAGE_DEPLOYMENT_PASSWORD","fixture");
        rejects(()->configuration.fcpStorageDeploymentAccess(credentialUrl,runtime,null));
        credentialUrl.withProperty("FCP_STORAGE_DEPLOYMENT_JDBC_URL","jdbc:mysql://127.0.0.1/test?createDatabaseIfNotExist=true");
        rejects(()->configuration.fcpStorageDeploymentAccess(credentialUrl,runtime,null));
        credentialUrl.withProperty("FCP_STORAGE_DEPLOYMENT_JDBC_URL","jdbc:mysql://127.0.0.1/test?sessionVariables=sql_mode=ANSI");
        rejects(()->configuration.fcpStorageDeploymentAccess(credentialUrl,runtime,null));
        var foreignDriver=new TestEnvironment().withProperty("fcp.runtime.storage-deployment.enabled","true").withProperty("FCP_STORAGE_DEPLOYMENT_JDBC_URL","jdbc:h2:mem:forbidden").withProperty("FCP_STORAGE_DEPLOYMENT_USER","fixture").withProperty("FCP_STORAGE_DEPLOYMENT_PASSWORD","fixture");
        rejects(()->configuration.fcpStorageDeploymentAccess(foreignDriver,runtime,null));
        for(var method:FcpStorageDeploymentConfiguration.class.getDeclaredMethods())if(method.isAnnotationPresent(org.springframework.context.annotation.Bean.class)&&DataSource.class.isAssignableFrom(method.getReturnType()))throw new AssertionError("Deployment exposed as runtime DataSource bean");
        var body=FcpStorageDeploymentController.class.getDeclaredMethod("body",String.class,Set.class);body.setAccessible(true);
        try{body.invoke(null,"{\"version\":\"1.0.0\",\"packageDigest\":\"abc\",\"password\":\"forbidden\"}",Set.of("version","packageDigest"));throw new AssertionError("HTTP credential field accepted");}
        catch(java.lang.reflect.InvocationTargetException expected){if(!(expected.getCause() instanceof FcpException failure)||!"INVALID_ARGUMENT".equals(failure.code))throw expected;}
        try{body.invoke(null,"{\"version\":\"1.0.0\",\"packageDigest\":\"abc\",\"sql\":\"DROP TABLE sys_user\"}",Set.of("version","packageDigest"));throw new AssertionError("HTTP SQL field accepted");}
        catch(java.lang.reflect.InvocationTargetException expected){if(!(expected.getCause() instanceof FcpException failure)||!"INVALID_ARGUMENT".equals(failure.code))throw expected;}
        var request=(jakarta.servlet.http.HttpServletRequest)Proxy.newProxyInstance(jakarta.servlet.http.HttpServletRequest.class.getClassLoader(),new Class<?>[]{jakarta.servlet.http.HttpServletRequest.class},(p,m,a)->m.getName().equals("getHeader")?null:null);
        try{new FcpStorageDeploymentController(null,null,null,null).requireExplicitToken(request);throw new AssertionError("Cookie-only request accepted");}catch(FcpException expected){if(!"UNAUTHENTICATED".equals(expected.code))throw expected;}
        System.out.println("PASS NativeStorageDeploymentBoundaryTest (configuration unit only; native HTTP/MySQL NOT_RUN)");
    }
}
