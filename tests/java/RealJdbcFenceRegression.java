import dev.fcp.dual.*;
import dev.fcp.school.*;
import fcp.*;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static fcp.Schema.*;

/** Real MySQL transactions with a latch just before Connection.commit().
 * Fixture scopes/identities in shared scenarios are explicit adapter fixtures;
 * native HTTP authentication and tenant isolation must be tested separately.
 */
public final class RealJdbcFenceRegression {
    static DataSource raw;
    static AtomicInteger commits=new AtomicInteger();
    static DataSource source(Properties config,EngineAdmissionFenceRegression.Store gate){
        return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class[]{DataSource.class},(o,m,a)->{
            if(!m.getName().equals("getConnection"))throw new UnsupportedOperationException(m.getName());
            Connection c=DriverManager.getConnection(config.getProperty("jdbc.url"),config.getProperty("jdbc.user"),config.getProperty("jdbc.password"));
            if(gate==null)return c;
            return Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(x,method,args)->{
                if(method.getName().equals("commit")){
                    if(c.getAutoCommit())throw new AssertionError("Expected actual open transaction");
                    commits.incrementAndGet();
                    if(gate.calls.get()==gate.blockAt){gate.entered.countDown();EngineAdmissionFenceRegression.await(gate.release);}
                }
                try{return method.invoke(c,args);}catch(InvocationTargetException e){throw e.getCause();}
            });
        });
    }
    static int requestCount(String key)throws Exception{
        try(Connection c=raw.getConnection();PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM fcp_dual_request WHERE request_key=?")){
            p.setString(1,key);try(ResultSet r=p.executeQuery()){r.next();return r.getInt(1);}
        }
    }
    static void setTeacherStatus(String expected,String next)throws Exception{
        try(Connection c=raw.getConnection();PreparedStatement p=c.prepareStatement("UPDATE sys_user SET status=? WHERE tenant_id='000000' AND user_id=942202 AND status=?")){
            p.setString(1,next);p.setString(2,expected);if(p.executeUpdate()!=1)throw new AssertionError("Synthetic teacher fixture preimage changed");
        }
    }
    static void revocation(Path runtime)throws Exception{
        var auth=new JdbcHostAuthorization(raw);var identity=auth.resolve("000000","942202");
        CountDownLatch dispatched=new CountDownLatch(1),resume=new CountDownLatch(1);AtomicInteger guards=new AtomicInteger();
        String key="fence-revoke-"+UUID.randomUUID();boolean changed=false;ExecutorService pool=Executors.newSingleThreadExecutor();
        try(Engine engine=SchoolSystem.create(runtime,new JdbcRecordStore(raw),(ctx,target,operation)->{
            var fresh=auth.resolve("000000","942202");
            if(!fresh.can(operation)||!fresh.classes().equals(identity.classes()))throw new FcpException("FORBIDDEN","Fresh native grant changed");
            if(operation.equals("create")&&guards.incrementAndGet()==1){dispatched.countDown();EngineAdmissionFenceRegression.await(resume);}
        })){
            var snapshot=engine.publish(identity.scope(),"school.java-only",0);
            Future<?> write=pool.submit(()->engine.invoke(identity.scope(),"evaluation","create",Json.obj("requestKey",key,"data",Json.obj("student","revoke-fixture","classId","class-a","indicator","\u8bfe\u5802\u53c2\u4e0e","score",80,"comment","Actual native grant recheck")),identity.attributes(),number(snapshot.get("generation")),str(snapshot.get("instance"))));
            EngineAdmissionFenceRegression.await(dispatched);setTeacherStatus("0","1");changed=true;resume.countDown();
            try{write.get(15,TimeUnit.SECONDS);throw new AssertionError("Revoked effect was accepted");}
            catch(ExecutionException e){EngineAdmissionFenceRegression.check(e.getCause() instanceof FcpException f&&f.code.equals("FORBIDDEN"),"actual MySQL account revocation rejects admitted write at effect boundary");}
            EngineAdmissionFenceRegression.check(requestCount(key)==0,"revoked JDBC write creates no request reservation or business transaction");
        }finally{resume.countDown();pool.shutdownNow();pool.awaitTermination(15,TimeUnit.SECONDS);if(changed)setTeacherStatus("1","0");}
        EngineAdmissionFenceRegression.check(auth.resolve("000000","942202").can("create"),"exact synthetic teacher active state restored");
    }
    static void rollback()throws Exception{
        String key="fence-rollback-"+UUID.randomUUID();String id=UUID.randomUUID().toString();
        var store=new JdbcRecordStore(raw);
        try{store.commit("tenant-fence-rollback","evaluation","100",key,"fixture-fingerprint",id,0,Json.obj("classId","x".repeat(200)),Json.obj());throw new AssertionError("Oversize generated class column unexpectedly accepted");}
        catch(FcpException e){EngineAdmissionFenceRegression.check(e.code.equals("STORAGE"),"actual MySQL rejects record after request reservation inside transaction");}
        EngineAdmissionFenceRegression.check(requestCount(key)==0,"actual failed record transaction rolls request reservation back");
        try{store.get("tenant-fence-rollback","evaluation",id);throw new AssertionError("Rolled back row visible");}
        catch(FcpException e){EngineAdmissionFenceRegression.check(e.code.equals("NOT_FOUND"),"failed actual transaction leaves no business row");}
    }
    public static void main(String[]args)throws Exception{
        Properties config=new Properties();try(var r=Files.newBufferedReader(Path.of(args[1]))){config.load(r);}
        if(!config.getProperty("jdbc.url").matches("jdbc:mysql://127\\.0\\.0\\.1:23306/fcp_ci_native_[a-f0-9]{8}\\?.*"))throw new IllegalArgumentException("Task-only MySQL required");
        raw=source(config,null);
        try(Connection c=raw.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT @@port,@@sql_mode")){
            r.next();if(r.getInt(1)!=23306||!r.getString(2).contains("STRICT"))throw new AssertionError("Dedicated strict MySQL expected");
        }
        EngineAdmissionFenceRegression.backingFactory=gate->new JdbcRecordStore(source(config,gate));
        EngineAdmissionFenceRegression.requestPrefix=UUID.randomUUID().toString().substring(0,8)+"-";
        Path runtime=Path.of(args[0]);
        EngineAdmissionFenceRegression.admissionAndPublication(runtime);
        EngineAdmissionFenceRegression.closeFence(runtime);
        EngineAdmissionFenceRegression.committedAcknowledgement(runtime);
        EngineAdmissionFenceRegression.publicationWins(runtime);
        EngineAdmissionFenceRegression.slowPreparation(runtime);
        EngineAdmissionFenceRegression.check(commits.get()==6,"six actual JDBC commit boundaries exercised without parallel atomic actions");
        revocation(runtime);rollback();
        System.out.println("RESULT "+EngineAdmissionFenceRegression.checks+" actual JDBC fence checks PASS; HTTP/browser separately required");
    }
}
