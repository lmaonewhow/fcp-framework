import fcp.education.FoundationStore;
import fcp.FcpException;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.io.PrintWriter;
import java.util.logging.Logger;

/** Real MySQL Connector/J test against ci_education's owned disposable native schema.
 * Does not create/replace native accounts or tokens. HTTP/login is a separate parent gate.
 */
public final class FoundationStoreNativeProbe {
    static final String TENANT="000000",ACTOR="1",SCHOOL="800001";
    static int checks;
    static final AtomicLong IDS=new AtomicLong(7_000_000_000_000_000L+Math.floorMod(System.nanoTime(),1_000_000_000_000L));
    static void check(boolean v){checks++;if(!v)throw new AssertionError("native check "+checks);}
    static String intent(){return "jdbc-"+UUID.randomUUID();}
    static String env(String name){String v=System.getenv(name);if(v==null||v.isEmpty())throw new IllegalStateException("Missing "+name);return v;}
    static final class RealSource implements DataSource {
        final String url,user,password;RealSource(String url,String user,String password){this.url=url;this.user=user;this.password=password;}
        public Connection getConnection()throws SQLException{return DriverManager.getConnection(url,user,password);}
        public Connection getConnection(String u,String p)throws SQLException{return DriverManager.getConnection(url,u,p);}
        public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter p){}public void setLoginTimeout(int n){}public int getLoginTimeout(){return 10;}public Logger getParentLogger(){return Logger.getGlobal();}public <T>T unwrap(Class<T> c)throws SQLException{throw new SQLException("Not a wrapper");}public boolean isWrapperFor(Class<?> c){return false;}
    }
    static long count(DataSource ds,String sql,String...values)throws SQLException {try(var c=ds.getConnection();var q=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)q.setString(i+1,values[i]);try(var r=q.executeQuery()){r.next();return r.getLong(1);}}}
    static void sql(DataSource ds,String sql,Object...values)throws SQLException {try(var c=ds.getConnection();var q=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);if(q.executeUpdate()!=1)throw new AssertionError("fixture update must match exactly one row");}}
    static String id(Map<String,Object> receipt){return (String)((Map<?,?>)receipt.get("record")).get("id");}
    static Map<String,Object> create(String request){return Map.of("intentId",request,"termId","810001","classCode",request,"name","Native JDBC class","gradeCode","G1");}
    static Map<String,Object> rename(String request,String object,String version){return Map.of("intentId",request,"id",object,"expectedRowVersion",version,"name","Native renamed "+request);}
    static void denied(Runnable call,String...codes){try{call.run();throw new AssertionError("Expected rejection");}catch(FcpException e){check(Arrays.asList(codes).contains(e.code));}}
    static Object result(Future<?> future)throws Exception {try{return future.get(15,TimeUnit.SECONDS);}catch(ExecutionException e){throw new RuntimeException("Native concurrent task failed",e.getCause());}}
    public static void main(String[] args)throws Exception {
        if(!"true".equals(System.getenv("GITHUB_ACTIONS")))throw new IllegalStateException("Disposable CI only");
        String database=env("FCP_EDUCATION_TEST_DATABASE");if(!database.matches("fcp_ci_[a-f0-9]{12}"))throw new IllegalStateException("Owned database required");
        RealSource ds=new RealSource(env("FCP_EDUCATION_TEST_URL"),env("FCP_EDUCATION_TEST_USER"),env("FCP_EDUCATION_TEST_PASSWORD"));
        try(var c=ds.getConnection();var q=c.createStatement();var r=q.executeQuery("SELECT DATABASE(),VERSION()")){r.next();check(database.equals(r.getString(1))&&r.getString(2).startsWith("8.4."));}
        String manifest=env("FCP_EDUCATION_TEST_MANIFEST");FoundationStore store=new FoundationStore(ds,()->Long.toString(IDS.incrementAndGet()),manifest);
        check(((List<?>)store.schools(TENANT,ACTOR).get("items")).size()>=2);
        denied(()->store.list(TENANT,"950001","800002","terms",null,10,null),"EDUCATION_FORBIDDEN");
        denied(()->store.list(TENANT,ACTOR,"800003","terms",null,10,null),"EDUCATION_FORBIDDEN","EDUCATION_NOT_FOUND");
        // Reach the native tenant boundary: every preceding ed_* authority row exists
        // for the distinct trailing-space tenant, but no native exact tenant exists.
        String tail="000000 ";
        sql(ds,"INSERT INTO ed_core_tenant_binding(tenant_id,host_kind,host_tenant_key,timezone,status,created_at) VALUES(?,'ruoyi-vue-plus',?,'UTC','ACTIVE',UTC_TIMESTAMP(6))",tail,tail);
        for(String fence:List.of("policy:tenant","cap:education.foundation"))sql(ds,"INSERT INTO ed_core_write_fence(tenant_id,fence_key,epoch,enabled,created_at) VALUES(?,?,0,1,UTC_TIMESTAMP(6))",tail,fence);
        sql(ds,"INSERT INTO ed_core_access_epoch(tenant_id,id,subject_key,epoch,changed_at,created_at,enabled) VALUES(?,?,?,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1)",tail,Long.toString(IDS.incrementAndGet()),ACTOR);
        sql(ds,"INSERT INTO ed_core_execution_grant(tenant_id,scope_key,capability_key,manifest_digest,epoch,enabled,created_at) VALUES(?,'tenant','education.foundation',?,0,1,UTC_TIMESTAMP(6))",tail,manifest);
        check(count(ds,"SELECT COUNT(*) FROM sys_tenant WHERE CAST(tenant_id AS BINARY)=CAST(? AS BINARY)",tail)==0);
        denied(()->store.schools(tail,ACTOR),"EDUCATION_FORBIDDEN");
        // More than 1000 earlier, unauthorized schools must not consume this actor's page.
        try(Connection c=ds.getConnection();PreparedStatement q=c.prepareStatement("INSERT INTO ed_education_org_unit(tenant_id,id,parent_id,org_code,org_kind,name,valid_from) VALUES('000000',?,NULL,?,'SCHOOL','Synthetic pagination decoy','2000-01-01 00:00:00')")) {
            c.setAutoCommit(false);
            for(int n=0;n<1002;n++){q.setLong(1,100000+n);q.setString(2,"probe-page-"+n);q.addBatch();}
            q.executeBatch();c.commit();
        }
        try {
            var page=store.schools(TENANT,"950001",null,1);
            var rows=(List<?>)page.get("items");
            check(rows.size()==1&&((Map<?,?>)rows.get(0)).get("id").equals(SCHOOL)&&page.get("nextAfterId")==null);
            var firstPage=store.schools(TENANT,ACTOR,null,1);
            check(((Map<?,?>)((List<?>)firstPage.get("items")).get(0)).get("id").equals("100000")&&"100000".equals(firstPage.get("nextAfterId")));
            var nextPage=store.schools(TENANT,ACTOR,"100000",1);
            check(((Map<?,?>)((List<?>)nextPage.get("items")).get(0)).get("id").equals("100001"));
        } finally {
            // Close only this probe's synthetic rows so later HTTP assertions remain unchanged.
            try(Connection c=ds.getConnection();PreparedStatement q=c.prepareStatement("UPDATE ed_education_org_unit SET valid_to='2001-01-01 00:00:00' WHERE tenant_id='000000' AND id BETWEEN 100000 AND 101001 AND org_code LIKE 'probe-page-%'")) {
                check(q.executeUpdate()==1002);
            }
        }
        String first=intent();var request=create(first);var receipt=store.command(TENANT,ACTOR,SCHOOL,"classes","create",request);String object=id(receipt);
        check(receipt.equals(store.command(TENANT,ACTOR,SCHOOL,"classes","create",request)));
        check(count(ds,"SELECT COUNT(*) FROM ed_core_audit_event WHERE tenant_id=? AND operation_id=?",TENANT,first)==1);
        check(count(ds,"SELECT COUNT(*) FROM ed_core_outbox WHERE tenant_id=? AND aggregate_type='class' AND aggregate_id=?",TENANT,object)==1);
        // A malformed organization in another authorized tenant school must not poison
        // this school's recursive traversal. Repair the synthetic corruption afterwards.
        String otherBad=Long.toString(IDS.incrementAndGet());
        sql(ds,"INSERT INTO ed_education_org_unit(tenant_id,id,parent_id,org_code,org_kind,name,valid_from,created_at) VALUES(?,?,800002,?,'BROKEN','Synthetic bad other-school row','2026-01-01',UTC_TIMESTAMP(6))",TENANT,otherBad,"bad-"+otherBad);
        try {check(!((List<?>)store.list(TENANT,ACTOR,SCHOOL,"organizations",null,100,null).get("items")).isEmpty());}
        finally {sql(ds,"UPDATE ed_education_org_unit SET org_kind='DEPARTMENT',row_version=1 WHERE tenant_id=? AND id=? AND org_kind='BROKEN'",TENANT,otherBad);}
        // Nested departments remain valid; product policy does not restrict child-kind combinations.
        String parentOrg=id(store.command(TENANT,ACTOR,SCHOOL,"organizations","create",Map.of("intentId",intent(),"parentId",SCHOOL,"orgCode",intent(),"orgKind","DEPARTMENT","name","Nested parent","validFrom","2026-01-01T00:00:00Z")));
        String childOrg=id(store.command(TENANT,ACTOR,SCHOOL,"organizations","create",Map.of("intentId",intent(),"parentId",parentOrg,"orgCode",intent(),"orgKind","CAMPUS","name","Nested child","validFrom","2026-01-01T00:00:00Z")));
        Set<String> seen=new HashSet<>();String cursor=null;
        for(int page=0;page<100;page++) {
            var listed=store.list(TENANT,ACTOR,SCHOOL,"organizations",cursor,1,null);
            for(Object row:(List<?>)listed.get("items"))check(seen.add((String)((Map<?,?>)row).get("id")));
            cursor=(String)listed.get("nextAfterId");if(cursor==null)break;
        }
        check(cursor==null&&seen.contains(parentOrg)&&seen.contains(childOrg)&&!seen.contains(otherBad));
        ExecutorService pool=Executors.newFixedThreadPool(3);
        try {
            // Both real transactions pass the absent-receipt lookup before either ledger
            // INSERT. Only the exact matching committed intent may recover from duplicate.
            CountDownLatch atLedger=new CountDownLatch(2);AtomicInteger admissions=new AtomicInteger();
            FoundationStore racing=new FoundationStore(ds,()->{if(admissions.incrementAndGet()<=2){atLedger.countDown();try{if(!atLedger.await(10,TimeUnit.SECONDS))throw new IllegalStateException("concurrent intent barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}return Long.toString(IDS.incrementAndGet());},manifest);
            String same=intent();var sameBody=create(same);
            Future<?> sameA=pool.submit(()->racing.command(TENANT,ACTOR,SCHOOL,"classes","create",sameBody));
            Future<?> sameB=pool.submit(()->racing.command(TENANT,ACTOR,SCHOOL,"classes","create",sameBody));
            Object sameResultA=result(sameA),sameResultB=result(sameB);check(sameResultA.equals(sameResultB));
            check(count(ds,"SELECT COUNT(*) FROM ed_education_class WHERE tenant_id=? AND school_id=? AND class_code=?",TENANT,SCHOOL,same)==1);
            check(count(ds,"SELECT COUNT(*) FROM ed_core_command_request WHERE tenant_id=? AND intent_id=?",TENANT,same)==1);
            check(count(ds,"SELECT COUNT(*) FROM ed_core_audit_event WHERE tenant_id=? AND operation_id=?",TENANT,same)==1);
            check(racing.securityEventFailures()==0);
            // Two actual domain transactions compete for the same CAS; exactly one commits.
            CountDownLatch start=new CountDownLatch(1);List<Future<String>> writers=new ArrayList<>();
            for(int n=0;n<2;n++){String i=intent();writers.add(pool.submit(()->{start.await();try{store.command(TENANT,ACTOR,null,"classes","rename",rename(i,object,"0"));return "COMMITTED";}catch(FcpException e){return e.code;}}));}
            start.countDown();List<String> outcomes=List.of((String)result(writers.get(0)),(String)result(writers.get(1)));
            check(Collections.frequency(outcomes,"COMMITTED")==1&&Collections.frequency(outcomes,"EDUCATION_CONFLICT")==1);
            check(store.detail(TENANT,ACTOR,null,"classes",object).get("rowVersion").equals("1"));
            // Force a real outbox unique-key failure after business UPDATE and audit INSERT.
            String collisionEvent=UUID.randomUUID().toString();
            sql(ds,"INSERT INTO ed_core_outbox(tenant_id,id,event_id,aggregate_type,aggregate_id,aggregate_seq,event_type,payload,status,available_at,created_at) VALUES(?,?,?,'class',?,3,'test.collision','{}','NEW',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",TENANT,Long.toString(IDS.incrementAndGet()),collisionEvent,object);
            String failed=intent();denied(()->store.command(TENANT,ACTOR,null,"classes","rename",rename(failed,object,"1")),"EDUCATION_CONFLICT");
            check(store.detail(TENANT,ACTOR,null,"classes",object).get("rowVersion").equals("1"));
            check(count(ds,"SELECT COUNT(*) FROM ed_core_audit_event WHERE tenant_id=? AND operation_id=?",TENANT,failed)==0);
            check(count(ds,"SELECT COUNT(*) FROM ed_core_command_request WHERE tenant_id=? AND intent_id=?",TENANT,failed)==0);
            // Native role disable competes with admitted business work. No mock identity or lock.
            String admittedClass=id(store.command(TENANT,ACTOR,SCHOOL,"classes","create",create(intent())));
            CountDownLatch admitted=new CountDownLatch(1),release=new CountDownLatch(1),revokeIssued=new CountDownLatch(1);AtomicBoolean hold=new AtomicBoolean(true);
            FoundationStore paused=new FoundationStore(ds,()->{if(hold.compareAndSet(true,false)){admitted.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}return Long.toString(IDS.incrementAndGet());},manifest);
            Future<?> command=pool.submit(()->paused.command(TENANT,ACTOR,null,"classes","rename",rename(intent(),admittedClass,"0")));
            check(admitted.await(10,TimeUnit.SECONDS));
            Future<?> revoke=pool.submit(()->{try(Connection c=ds.getConnection()){c.setAutoCommit(false);try(var q=c.prepareStatement("UPDATE sys_role SET status='1' WHERE role_id=987101 AND CAST(tenant_id AS BINARY)=CAST('000000' AS BINARY) AND status='0'")){revokeIssued.countDown();if(q.executeUpdate()!=1)throw new AssertionError("native role fixture mismatch");}c.commit();return true;}});
            try {
                check(revokeIssued.await(5,TimeUnit.SECONDS));
                try{revoke.get(300,TimeUnit.MILLISECONDS);throw new AssertionError("Native revoke did not wait for admitted row locks");}catch(TimeoutException expected){checks++;}
                release.countDown();result(command);result(revoke);
                denied(()->store.command(TENANT,ACTOR,null,"classes","rename",rename(intent(),admittedClass,"1")),"EDUCATION_FORBIDDEN");
            } finally {release.countDown();try{result(command);}catch(Exception ignored){}try{result(revoke);}catch(Exception ignored){}sql(ds,"UPDATE sys_role SET status='0' WHERE role_id=987101 AND CAST(tenant_id AS BINARY)=CAST('000000' AS BINARY) AND status='1'");}
        } finally {pool.shutdownNow();pool.awaitTermination(15,TimeUnit.SECONDS);}
        check(store.securityEventFailures()==0);
        System.out.println("{\"status\":\"PASS\",\"scope\":\"REAL_MYSQL_FOUNDATION_JDBC_NOT_HTTP_LOGIN\",\"checks\":"+checks+",\"unknownCommitFaultInjection\":\"NOT_RUN\"}");
    }
}
