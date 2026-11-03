import dev.fcp.school.JdbcPluginStateStore;
import fcp.FcpException;
import fcp.state.PluginStateStore.*;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Opt-in REAL MySQL adapter tests. Requires an initialized disposable fcp_ci_* database.
 * The supplied runtime account must have DML access and must NOT have CREATE TABLE permission.
 */
public final class RealMySqlRegression {
    static int count;
    static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);count++;System.out.println("PASS "+name);}
    static void denied(String code,Runnable action){try{action.run();throw new AssertionError("expected "+code);}catch(FcpException e){check(e.code.equals(code),"actual DB denial "+code);}}
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Supply private JDBC properties");
        Properties config=new Properties();try(var reader=Files.newBufferedReader(Path.of(args[0]))){config.load(reader);}
        String url=config.getProperty("jdbc.url"),user=config.getProperty("jdbc.user"),password=config.getProperty("jdbc.password");
        DataSource dataSource=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class[]{DataSource.class},(o,m,a)->{
            if(m.getName().equals("getConnection"))return DriverManager.getConnection(url,user,password);throw new UnsupportedOperationException(m.getName());});
        try(Connection c=dataSource.getConnection()){
            check(c.getCatalog()!=null&&c.getCatalog().matches("fcp_ci_[a-z0-9_]+"),"isolated fcp_ci database required");
            check(c.getMetaData().getDatabaseProductName().equals("MySQL")&&c.getMetaData().getDatabaseMajorVersion()>=8,"actual MySQL 8+ JDBC server");
            try(Statement s=c.createStatement()){
                try{s.execute("CREATE TABLE fcp_ci_forbidden_ddl (id INT)");throw new AssertionError("runtime unexpectedly has DDL permission");}
                catch(SQLException denied){check(denied.getErrorCode()==1142,"runtime CREATE TABLE permission rejected by MySQL");}
            }
        }
        String unique=UUID.randomUUID().toString().replace("-","");var key=new Key("tenant-ci-"+unique,"evaluation.stats");
        var store=new JdbcPluginStateStore(dataSource);
        check(store.getOrCreate(key,false).equals(new State(false,1)),"actual DB initializes disabled state");
        check(store.getOrCreate(key,true).equals(new State(false,1)),"actual DB does not overwrite existing desired state");
        var start=new CountDownLatch(1);AtomicInteger winners=new AtomicInteger(),conflicts=new AtomicInteger();var failures=new ConcurrentLinkedQueue<Throwable>();
        ExecutorService workers=Executors.newFixedThreadPool(8);
        try{
            List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<8;i++)futures.add(workers.submit(()->{try{start.await();store.compareAndSet(key,1,true,"test-user");winners.incrementAndGet();}catch(FcpException e){if(e.code.equals("STALE_COMPOSITION"))conflicts.incrementAndGet();else failures.add(e);}catch(Throwable e){failures.add(e);}}));
            start.countDown();for(Future<?> future:futures)future.get(20,TimeUnit.SECONDS);
        }finally{workers.shutdownNow();}
        check(failures.isEmpty()&&winners.get()==1&&conflicts.get()==7,"actual concurrent SQL CAS has exactly one winner");
        check(new JdbcPluginStateStore(dataSource).getOrCreate(key,false).equals(new State(true,2)),"new Java adapter observes persisted DB state");
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM fcp_plugin_state_audit WHERE scope_id=? AND plugin_id=?")){
            p.setString(1,key.scope());p.setString(2,key.plugin());try(ResultSet rows=p.executeQuery()){rows.next();check(rows.getInt(1)==1,"actual DB contains one committed audit event");}
        }
        // Reserve the next audit primary key to force an actual MySQL duplicate-key error after state UPDATE.
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement("INSERT INTO fcp_plugin_state_audit(scope_id,plugin_id,revision,enabled,actor) VALUES(?,?,3,0,'fault-fixture')")){
            p.setString(1,key.scope());p.setString(2,key.plugin());p.executeUpdate();
        }
        denied("STATE_UNAVAILABLE",()->store.compareAndSet(key,2,false,"test-user"));
        check(store.getOrCreate(key,false).equals(new State(true,2)),"actual SQL transaction rolls state back when audit insert fails");
        var other=new Key("tenant-other-"+unique,"evaluation.stats");check(store.getOrCreate(other,false).equals(new State(false,1)),"actual DB separates scope state");
        System.out.println("RESULT "+count+" actual MySQL adapter checks PASS; native browser/auth workflow is a separate test");
    }
}
