import fcp.*;
import fcp.host.*;
import fcp.storage.*;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;

/** Real MySQL with an explicitly labelled JDBC driver-fault decorator.
 * Every SQL statement and commit reaches Connector/J/MySQL. No ResultSet is fabricated.
 * This is NOT a database-server crash, network proxy, power-loss or native HTTP test.
 * Business records are created exclusively through the production storage API.
 */
public final class MysqlFailureBoundariesTest {
    private static final List<Map<String,Object>> checks=new ArrayList<>();
    private static final Map<String,Object> report=new LinkedHashMap<>();
    private static Path output;
    static void check(String name,boolean ok){if(!ok)throw new AssertionError(name);checks.add(Map.of("name",name,"status","PASS"));write();}
    static void write(){if(output!=null)try{Files.createDirectories(output.getParent());Files.writeString(output,Json.write(report)+"\n");}catch(Exception e){throw new RuntimeException("Unable to write failure-boundary evidence",e);}}
    static void rejects(String code,Runnable action){try{action.run();throw new AssertionError("Expected "+code);}catch(FcpException e){if(!code.equals(e.code))throw e;}}
    static TrustedContext context(String tenant,TrustedContext.Kind kind,String principal){return new TrustedContext("failure-"+UUID.randomUUID(),"failure-trace",tenant,new TrustedContext.Principal(kind,principal),1,Set.of("class:one"),Instant.now().plusSeconds(300));}
    static final class Host implements HostCapabilities {
        // Explicit unit-controlled policy; native policy coverage is reported separately.
        volatile boolean revoked;
        final Set<String> revokedPrincipals=ConcurrentHashMap.newKeySet();
        static String principal(TrustedContext context){return context.tenantId()+"/"+context.principal().kind()+"/"+context.principal().id();}
        void revoke(TrustedContext context){revokedPrincipals.add(principal(context));}
        void regrant(TrustedContext context){revokedPrincipals.remove(principal(context));}
        public Descriptor descriptor(){return new Descriptor("failure-test","1","fcp.host/1","embedded-single-instance",Map.of());}
        public Clock clock(){return Clock.systemUTC();}
        public Decision authorize(TrustedContext context,Grant grant){return new Decision(!revoked&&!revokedPrincipals.contains(principal(context)),1);}
        public void audit(Audit audit){}
    }
    static class Source implements DataSource {
        final String url,user,password;
        Source(String url,String user,String password){this.url=url;this.user=user;this.password=password;}
        public Connection getConnection()throws SQLException{return DriverManager.getConnection(url,user,password);}
        public Connection getConnection(String user,String password){throw new UnsupportedOperationException();}
        public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter p){}public void setLoginTimeout(int seconds){}public int getLoginTimeout(){return 0;}public Logger getParentLogger(){return Logger.getGlobal();}public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("Not a wrapper");}public boolean isWrapperFor(Class<?> type){return false;}
    }
    static Object invoke(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
    /** Throws only AFTER a successful real commit/DDL acknowledgement, simulating the driver
     * losing that acknowledgement before the production caller receives it. */
    static final class FaultSource extends Source {
        final Source delegate;final boolean commitFault;final int ddlFault;
        final AtomicInteger commits=new AtomicInteger(),ddls=new AtomicInteger(),faults=new AtomicInteger();
        FaultSource(Source delegate,boolean commitFault,int ddlFault){super(delegate.url,delegate.user,delegate.password);this.delegate=delegate;this.commitFault=commitFault;this.ddlFault=ddlFault;}
        @Override public Connection getConnection()throws SQLException{
            Connection real=delegate.getConnection();
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                Object value=invoke(real,m,a);
                if(m.getName().equals("commit")){commits.incrementAndGet();if(commitFault&&faults.compareAndSet(0,1))throw new SQLException("Injected driver response loss AFTER real MySQL commit","08006");}
                if(m.getName().equals("createStatement")){
                    Statement statement=(Statement)value;
                    return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(sp,sm,sa)->{
                        Object result=invoke(statement,sm,sa);
                        if(sm.getName().equals("execute")&&sa!=null&&sa.length>0&&sa[0] instanceof String sql&&(sql.startsWith("CREATE TABLE ")||sql.startsWith("ALTER TABLE ")||sql.startsWith("CREATE INDEX "))){
                            int n=ddls.incrementAndGet();if(n==ddlFault&&faults.compareAndSet(0,1))throw new SQLException("Injected driver response loss AFTER real MySQL DDL step "+n,"08006");
                        }
                        return result;
                    });
                }
                return value;
            });
        }
    }
    /** Pauses only AFTER the real transaction has staged its audit INSERT, before the
     * production storage implementation's final authorization check and commit.
     * All statements/results delegate to Connector/J. The same connection proves its
     * staged record, receipt and audit exist, while independent connections see none.
     */
    static final class PreCommitGateSource extends Source {
        final Source delegate;final String plugin,recordId,receipt,recordSql;final String[] recordArgs;
        final CountDownLatch staged=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicBoolean once=new AtomicBoolean();final AtomicInteger commits=new AtomicInteger(),rollbacks=new AtomicInteger();
        final AtomicReference<List<Long>> stagedCounts=new AtomicReference<>();
        PreCommitGateSource(Source delegate,String plugin,String recordId,String receipt,String recordSql,String... recordArgs){
            super(delegate.url,delegate.user,delegate.password);this.delegate=delegate;this.plugin=plugin;this.recordId=recordId;this.receipt=receipt;this.recordSql=recordSql;this.recordArgs=recordArgs.clone();
        }
        @Override public Connection getConnection()throws SQLException{
            Connection real=delegate.getConnection();
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                Object value=invoke(real,m,a);
                if(m.getName().equals("commit"))commits.incrementAndGet();
                if(m.getName().equals("rollback"))rollbacks.incrementAndGet();
                if(m.getName().equals("prepareStatement")&&a!=null&&a.length>0&&a[0] instanceof String sql&&sql.startsWith("INSERT INTO fcp_plugin_storage_audit(")){
                    PreparedStatement statement=(PreparedStatement)value;
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(sp,sm,sa)->{
                        Object result=invoke(statement,sm,sa);
                        if(sm.getName().equals("executeUpdate")&&once.compareAndSet(false,true)){
                            if(real.getAutoCommit())throw new AssertionError("Gate reached outside a real MySQL transaction");
                            stagedCounts.set(List.of(count(real,recordSql,recordArgs),
                                count(real,"SELECT COUNT(*) FROM fcp_plugin_receipt WHERE receipt_id=?",receipt),
                                count(real,"SELECT COUNT(*) FROM fcp_plugin_storage_audit WHERE plugin_id=? AND record_id=?",plugin,recordId)));
                            staged.countDown();
                            if(!release.await(45,TimeUnit.SECONDS))throw new AssertionError("Pre-commit authorization gate timed out");
                        }
                        return result;
                    });
                }
                return value;
            });
        }
    }
    static Map<String,Object> declaration(){return Map.of("schemaVersion",1,"compatibleVersions",List.of(1),"tables",List.of(Map.of("name","roster","fields",List.of(Map.of("name","name","type","string","nullable",false,"maxLength",100),Map.of("name","note","type","string","nullable",true,"maxLength",100)),"indexes",List.of(Map.of("name","by_name","fields",List.of("name"),"unique",true)))),"migrations",List.of());}
    static long count(Source source,String sql,String... args)throws Exception{try(Connection c=source.getConnection()){return count(c,sql,args);}}
    static long count(Connection connection,String sql,String... args)throws SQLException{try(PreparedStatement p=connection.prepareStatement(sql)){p.setQueryTimeout(10);for(int i=0;i<args.length;i++)p.setString(i+1,args[i]);try(ResultSet r=p.executeQuery()){r.next();return r.getLong(1);}}}
    static void inFlightRevocation(Source runtime,String plugin,String collection,boolean physical,TrustedContext.Kind kind)throws Exception{
        String label=(physical?"physical":"logical")+" "+kind,recordId="revoked_"+kind.name().toLowerCase(Locale.ROOT),key="precommit-"+recordId;
        var context=context("failure_tenant",kind,"inflight_"+kind.name().toLowerCase(Locale.ROOT));var host=new Host();
        List<Object> receiptParts=new ArrayList<>();if(physical)receiptParts.add("physical");receiptParts.addAll(List.of(context.tenantId(),plugin,kind.name(),context.principal().id(),"class:one","write",key));String receipt=JdbcPluginStorage.hash(Json.write(receiptParts));
        String recordSql;String[] recordArgs;
        if(physical){String table=PhysicalSchemaMigrator.plan(plugin,collection,List.of(new PhysicalSchemaMigrator.Step(1,PhysicalSchemaMigrator.Kind.CREATE,null,null))).table();recordSql="SELECT COUNT(*) FROM `"+table+"` WHERE tenant_id=? AND data_scope=? AND record_id=?";recordArgs=new String[]{context.tenantId(),"class:one",recordId};}
        else{recordSql="SELECT COUNT(*) FROM fcp_plugin_record WHERE tenant_id=? AND plugin_id=? AND collection_id=? AND data_scope=? AND record_id=?";recordArgs=new String[]{context.tenantId(),plugin,collection,"class:one",recordId};}
        var gate=new PreCommitGateSource(runtime,plugin,recordId,receipt,recordSql,recordArgs);
        var gatedSession=new JdbcPluginStorage(gate,host).bind(plugin,context,"class:one");Map<String,Object> body=Map.of("name","Revocation "+kind);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try{
            Future<String> pending=pool.submit(()->{try{gatedSession.put(collection,recordId,body,0,key);return "APPLIED";}catch(FcpException error){return error.code;}});
            if(!gate.staged.await(20,TimeUnit.SECONDS)){if(pending.isDone())throw new AssertionError("Storage operation did not reach gate: "+pending.get());throw new AssertionError("Real transaction did not reach pre-commit gate");}
            check(label+" real transaction has staged record, receipt and audit before revocation",List.of(1L,1L,1L).equals(gate.stagedCounts.get())&&!pending.isDone()&&gate.commits.get()==0&&gate.rollbacks.get()==0);
            check(label+" independent MySQL connections cannot see the uncommitted write",count(runtime,recordSql,recordArgs)==0&&count(runtime,"SELECT COUNT(*) FROM fcp_plugin_receipt WHERE receipt_id=?",receipt)==0&&count(runtime,"SELECT COUNT(*) FROM fcp_plugin_storage_audit WHERE plugin_id=? AND record_id=?",plugin,recordId)==0);
            // This is a deliberate storage-layer authorization fixture, not a native role
            // update, task-grant SQL mutation, runtime drain operation or UI test.
            host.revoke(context);gate.release.countDown();
            check(label+" in-flight revoked principal is denied at final authorization",pending.get(20,TimeUnit.SECONDS).equals("PERMISSION_DENIED"));
            check(label+" real MySQL transaction rolls back without committing",gate.commits.get()==0&&gate.rollbacks.get()==1);
            check(label+" revoked write leaves no business row, receipt or audit",count(runtime,recordSql,recordArgs)==0&&count(runtime,"SELECT COUNT(*) FROM fcp_plugin_receipt WHERE receipt_id=?",receipt)==0&&count(runtime,"SELECT COUNT(*) FROM fcp_plugin_storage_audit WHERE plugin_id=? AND record_id=?",plugin,recordId)==0);
            var healthy=new JdbcPluginStorage(runtime,host).bind(plugin,context,"class:one");
            rejects("PERMISSION_DENIED",()->healthy.put(collection,recordId,body,0,key));
            check(label+" denied identity cannot retry while still revoked",true);
            host.regrant(context);var restored=healthy.put(collection,recordId,body,0,key);var replay=healthy.put(collection,recordId,body,0,key);
            check(label+" explicit regrant lets the same key commit once and replay",restored.revision()==1&&restored.equals(replay)&&healthy.get(collection,recordId).orElseThrow().revision()==1);
            check(label+" reauthorized commit has one row, one receipt and one identity-specific audit",count(runtime,recordSql,recordArgs)==1&&count(runtime,"SELECT COUNT(*) FROM fcp_plugin_receipt WHERE receipt_id=?",receipt)==1&&count(runtime,"SELECT COUNT(*) FROM fcp_plugin_storage_audit WHERE plugin_id=? AND record_id=? AND principal_id=? AND principal_kind=?",plugin,recordId,context.principal().id(),kind.name())==1);
        }finally{gate.release.countDown();pool.shutdownNow();if(!pool.awaitTermination(10,TimeUnit.SECONDS))throw new AssertionError("Pre-commit worker did not terminate");host.regrant(context);}
    }
    static void unknownCommit(Source runtime,Host host,String plugin,String collection,TrustedContext context,boolean physical)throws Exception{
        FaultSource fault=new FaultSource(runtime,true,0);var unknown=new JdbcPluginStorage(fault,host).bind(plugin,context,"class:one");
        String label=physical?"physical":"logical",key="lost-"+label;
        rejects("OUTCOME_UNKNOWN",()->unknown.put(collection,"committed",Map.of("name","Committed"),0,key));
        check(label+" commit reached actual MySQL before exactly one driver fault",fault.commits.get()==1&&fault.faults.get()==1);
        var healthy=new JdbcPluginStorage(runtime,host).bind(plugin,context,"class:one");
        var observed=healthy.get(collection,"committed").orElseThrow();check(label+" fresh connection reads committed revision one",observed.revision()==1&&"Committed".equals(observed.body().get("name")));
        var replay=healthy.put(collection,"committed",Map.of("name","Committed"),0,key);
        check(label+" same key recovers exact receipt without duplicate mutation",replay.equals(observed)&&healthy.get(collection,"committed").orElseThrow().revision()==1);
        rejects("IDEMPOTENCY_CONFLICT",()->healthy.put(collection,"committed",Map.of("name","Changed"),0,key));
        check(label+" changed request under uncertain key is rejected",true);
        check(label+" original write produced exactly one atomic audit",count(runtime,"SELECT COUNT(*) FROM fcp_plugin_storage_audit WHERE plugin_id=? AND record_id='committed'",plugin)==1);
        host.revoked=true;try{rejects("PERMISSION_DENIED",()->healthy.put(collection,"committed",Map.of("name","Committed"),0,key));}finally{host.revoked=false;}
        check(label+" receipt replay still requires live authorization (controlled test policy)",true);
    }
    static void concurrentCas(Source runtime,Host host,String plugin,String collection,TrustedContext context)throws Exception{
        var initial=new JdbcPluginStorage(runtime,host).bind(plugin,context,"class:one");initial.put(collection,"concurrent",Map.of("name","Before"),0,"cas-create");
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            List<Future<String>> futures=new ArrayList<>();for(int i=0;i<2;i++){final int id=i;futures.add(pool.submit(()->{
                var independent=new JdbcPluginStorage(runtime,host).bind(plugin,context,"class:one");ready.countDown();if(!start.await(15,TimeUnit.SECONDS))throw new AssertionError("Concurrent start timeout");
                try{independent.put(collection,"concurrent",Map.of("name","Writer"+id),1,"cas-writer-"+id);return "APPLIED";}catch(FcpException e){return e.code;}
            }));}
            if(!ready.await(15,TimeUnit.SECONDS))throw new AssertionError("Workers not ready");start.countDown();List<String> results=new ArrayList<>();for(var future:futures)results.add(future.get(45,TimeUnit.SECONDS));Collections.sort(results);
            check(collection+" two actual MySQL transactions yield one CAS winner and one conflict",results.equals(List.of("APPLIED","VERSION_CONFLICT")));
            check(collection+" racing writes leave exactly revision two",initial.get(collection,"concurrent").orElseThrow().revision()==2);
            check(collection+" losing transaction produces no mutation audit",count(runtime,"SELECT COUNT(*) FROM fcp_plugin_storage_audit WHERE plugin_id=? AND record_id='concurrent'",plugin)==2);
        }finally{start.countDown();pool.shutdownNow();if(!pool.awaitTermination(10,TimeUnit.SECONDS))throw new AssertionError("Concurrent transaction workers did not terminate");}
    }
    static void ddlRecovery(Source runtime,Source deployment,Host host,String plugin,TrustedContext context)throws Exception{
        FaultSource fault=new FaultSource(deployment,false,3);var failed=new StorageDeploymentService(fault,host);var plan=failed.plan(plugin,declaration());
        rejects("STORAGE_UNAVAILABLE",()->failed.apply(context,plan,plan.digest()));
        check("DDL step three executed on real MySQL before injected driver response loss",fault.ddls.get()==3&&fault.faults.get()==1);
        String table=plan.tables().get(0).table();
        check("failed migration retains exactly two COMPLETE and one FAILED journal steps",count(runtime,"SELECT COUNT(*) FROM fcp_plugin_ddl_step WHERE table_name=? AND phase='COMPLETE'",table)==2&&count(runtime,"SELECT COUNT(*) FROM fcp_plugin_ddl_step WHERE table_name=? AND target_version=3 AND phase='FAILED'",table)==1);
        check("failed deployment does not publish a storage release",count(runtime,"SELECT COUNT(*) FROM fcp_plugin_storage_release WHERE plugin_id=?",plugin)==0);
        rejects("STORAGE_NOT_DEPLOYED",()->new JdbcStorageCompatibility(runtime).verify(plugin,"1.0.0","",declaration()));
        check("runtime compatibility gate rejects incomplete deployment (not HTTP READY evidence)",true);
        var healthy=new StorageDeploymentService(deployment,host);healthy.apply(context,plan,plan.digest());healthy.apply(context,plan,plan.digest());
        new JdbcStorageCompatibility(runtime).verify(plugin,"1.0.0","",declaration());
        check("exact reviewed plan reconciles committed DDL and is safely replayable",count(runtime,"SELECT COUNT(*) FROM fcp_plugin_ddl_step WHERE table_name=? AND phase<>'COMPLETE'",table)==0&&count(runtime,"SELECT COUNT(*) FROM fcp_plugin_storage_release WHERE plugin_id=? AND schema_version=1",plugin)==1);
        var session=new JdbcPluginStorage(runtime,host).bind(plugin,context,"class:one");session.put("roster","after_recovery",Map.of("name","Recovered"),0,"recovered-write");check("production storage API writes and reads recovered physical schema",session.get("roster","after_recovery").orElseThrow().revision()==1);
    }
    public static void main(String[] args)throws Exception{
        output=args.length==1?Path.of(args[0]).toAbsolutePath():null;
        report.put("status","NOT_RUN");report.put("scope","Actual MySQL/Connector-J; controlled HostCapabilities; JDBC post-commit/post-DDL driver faults and pre-commit USER/TASK revocation, not server crash or native HTTP");report.put("checks",checks);report.put("production","NOT_ACCEPTED");report.put("nativeHttp","NOT_RUN");report.put("serverPowerLoss","NOT_RUN");report.put("storageInFlightRevocation","NOT_RUN");report.put("nativeDrainingRevocation","NOT_RUN");write();
        String url=System.getenv("FCP_STORAGE_TEST_URL");
        if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/fcp_ci_[a-f0-9]{12}(\\?.*)?")){report.put("status","BLOCKED");report.put("reason","Requires explicitly supplied disposable loopback fcp_ci_<12 hex> database");write();System.out.println(Json.write(report));System.exit(2);}
        long started=System.nanoTime();
        try{
            var runtime=new Source(url,System.getenv("FCP_STORAGE_TEST_USER"),System.getenv("FCP_STORAGE_TEST_PASSWORD"));var deploy=new Source(url,System.getenv("FCP_STORAGE_TEST_DEPLOY_USER"),System.getenv("FCP_STORAGE_TEST_DEPLOY_PASSWORD"));
            if(runtime.user==null||deploy.user==null||runtime.user.equals(deploy.user))throw new IllegalArgumentException("Distinct explicit runtime and deployment identities required");
            try(Connection c=runtime.getConnection()){check("underlying connection is genuine MySQL",c.getMetaData().getDatabaseProductName().equals("MySQL"));report.put("databaseVersion",c.getMetaData().getDatabaseProductVersion());report.put("driver",c.getMetaData().getDriverName());check("actual MySQL isolation is REPEATABLE READ",c.getTransactionIsolation()==Connection.TRANSACTION_REPEATABLE_READ);}
            var host=new Host();var context=context("failure_tenant",TrustedContext.Kind.USER,"failure_user");String nonce=UUID.randomUUID().toString().replace("-","");String logical="failure.logical."+nonce,physical="failure.physical."+nonce;
            new AdditiveSchemaMigrator(deploy,host).apply(context,logical,new StorageSchema("students",1,Map.of("name",StorageSchema.Type.STRING),Set.of("name")),0);
            unknownCommit(runtime,host,logical,"students",context,false);concurrentCas(runtime,host,logical,"students",context);
            ddlRecovery(runtime,deploy,host,physical,context);
            unknownCommit(runtime,host,physical,"roster",context,true);concurrentCas(runtime,host,physical,"roster",context);
            report.put("storageInFlightRevocation","RUNNING");write();
            for(var kind:List.of(TrustedContext.Kind.USER,TrustedContext.Kind.TASK)){inFlightRevocation(runtime,logical,"students",false,kind);inFlightRevocation(runtime,physical,"roster",true,kind);}
            report.put("storageInFlightRevocation","PASS");report.put("storageInFlightRevocationCases",4);
            report.put("status","PASS");report.put("totalChecks",checks.size());
        }catch(Throwable error){report.put("status","FAIL");if("RUNNING".equals(report.get("storageInFlightRevocation")))report.put("storageInFlightRevocation","FAIL");report.put("errorType",error.getClass().getSimpleName());if(error instanceof FcpException fcp)report.put("fcpCode",fcp.code);report.put("error",error instanceof SQLException?"Actual MySQL operation failed; credentials omitted":error.getMessage());throw error;}
        finally{report.put("elapsedSeconds",(System.nanoTime()-started)/1_000_000_000.0);write();}
        System.out.println(Json.write(report));
    }
}
