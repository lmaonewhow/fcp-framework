import fcp.education.RosterStore;
import fcp.FcpException;
import fcp.Json;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.io.PrintWriter;
import java.util.logging.Logger;

/** Only real Connector/J and this run's fresh disposable MySQL 8.4 schema.
 * Native HTTP authentication is tested separately; no fake tokens or in-memory stores.
 * This probe never creates native accounts/roles or authorizes itself. The reviewed CI fixture
 * has already provisioned exact 9881xx roles, purpose mappings, schools and actual-JAR grants.
 */
public final class RosterStoreNativeProbe {
    static final String TENANT="000000",ADMIN="1",SCHOOL_ADMIN="950001",VIEWER="950002",SCHOOL="800001";
    static final String FIRST="9007199254740993",OTHER="9007199254740994",LEFT="18446744073709551614",LAST="18446744073709551615";
    static int checks;
    static final AtomicLong IDS=new AtomicLong(7_500_000_000_000_000L+Math.floorMod(System.nanoTime(),1_000_000_000_000L));
    static void check(boolean value){checks++;if(!value)throw new AssertionError("roster native check "+checks);}
    static String env(String name){String value=System.getenv(name);if(value==null||value.isEmpty())throw new IllegalStateException("Missing "+name);return value;}
    static final class RealSource implements DataSource {
        final String url,user,password;
        final AtomicLong observedReadConnection=new AtomicLong();
        RealSource(String url,String user,String password){this.url=url;this.user=user;this.password=password;}
        public Connection getConnection()throws SQLException {
            Connection connection=DriverManager.getConnection(url,user,password);
            // Observe the real server connection ID, not a replacement Connection/Statement.
            if(Thread.currentThread().getName().equals("roster-admitted-read"))try(var q=connection.createStatement();var r=q.executeQuery("SELECT CONNECTION_ID()")){r.next();observedReadConnection.set(r.getLong(1));}
            return connection;
        }
        public Connection getConnection(String u,String p)throws SQLException{return DriverManager.getConnection(url,u,p);}
        public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter p){}public void setLoginTimeout(int n){}public int getLoginTimeout(){return 10;}public Logger getParentLogger(){return Logger.getGlobal();}public <T>T unwrap(Class<T> c)throws SQLException{throw new SQLException("Not a wrapper");}public boolean isWrapperFor(Class<?> c){return false;}
    }
    static void sql(DataSource ds,String sql,Object...values)throws SQLException {
        try(var c=ds.getConnection();var q=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);if(q.executeUpdate()!=1)throw new AssertionError("Expected one fixture row");}
    }
    static long count(DataSource ds,String sql,Object...values)throws SQLException {
        try(var c=ds.getConnection();var q=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);try(var r=q.executeQuery()){r.next();return r.getLong(1);}}
    }
    static void denied(Runnable call,String...codes){try{call.run();throw new AssertionError("Expected rejection");}catch(FcpException error){check(Arrays.asList(codes).contains(error.code));}}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> items(Map<String,Object> page){return (List<Map<String,Object>>)page.get("items");}
    static void privateFieldsAbsent(Object value) {
        if(value instanceof Map<?,?> row){for(var entry:row.entrySet()){check(!Set.of("personId","displayName","studentCode","subjectCode","changeReason","privateRef","private_ref","person_id","display_name","student_code","subject_code","change_reason","accountLinks","externalMap","guardian").contains(entry.getKey()));privateFieldsAbsent(entry.getValue());}}
        else if(value instanceof List<?> rows)for(Object row:rows)privateFieldsAbsent(row);
    }
    static void metadata(Map<String,Object> row,String kind) {
        Set<String> keys=new HashSet<>(Set.of("id","schoolId","validFrom","validTo","createdAt","rowVersion","personDisplay"));
        keys.addAll(switch(kind){case "enrollments"->Set.of("admissionKey","status");case "memberships"->Set.of("enrollmentId","classId","className","termId","termCode","termStatus");default->Set.of("classId","className","termId","termCode","termStatus","roleCode");});
        check(row.keySet().equals(keys));check(row.get("id") instanceof String&&row.get("rowVersion") instanceof String);
        check(row.get("personDisplay").equals(Map.of("status","PENDING","reason","PERSON_DISPLAY_POLICY_PENDING")));privateFieldsAbsent(row);
    }
    static Object result(Future<?> future)throws Exception{return future.get(15,TimeUnit.SECONDS);}
    static void awaitPersonRead(RealSource ds)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<until){long id=ds.observedReadConnection.get();
            if(id>0&&count(ds,"SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID=CAST(? AS UNSIGNED) AND INFO LIKE 'SELECT id,status FROM ed_identity_person%'",id)==1){checks++;return;}
            Thread.sleep(20);
        }
        throw new AssertionError("Real server did not reach person resolver lock wait");
    }
    static void admittedReadBlocksNativeRevocation(RealSource ds,RosterStore store)throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);Future<?> read=null,revoke=null;
        try(Connection blocker=ds.getConnection()) {
            blocker.setAutoCommit(false);
            try(var q=blocker.prepareStatement("SELECT id FROM ed_identity_person WHERE tenant_id=? AND id=880001 FOR UPDATE")){q.setString(1,TENANT);try(var r=q.executeQuery()){check(r.next());}}
            read=pool.submit(()->{Thread.currentThread().setName("roster-admitted-read");return store.detail(TENANT,ADMIN,SCHOOL,"enrollments",FIRST);});
            awaitPersonRead(ds); // Proves this real read already holds native authority locks.
            CountDownLatch issued=new CountDownLatch(1);
            revoke=pool.submit(()->{try(Connection c=ds.getConnection()){
                c.setAutoCommit(false);try(var q=c.prepareStatement("UPDATE sys_role SET status='1' WHERE role_id=988101 AND CAST(tenant_id AS BINARY)=CAST('000000' AS BINARY) AND role_key='edu_roster_ci_988101' AND role_name='Roster tenant admin' AND del_flag='0' AND status='0'")){issued.countDown();if(q.executeUpdate()!=1)throw new AssertionError("Native role fixture mismatch");}c.commit();return true;}});
            check(issued.await(5,TimeUnit.SECONDS));
            try{revoke.get(250,TimeUnit.MILLISECONDS);throw new AssertionError("Revocation did not wait for admitted read");}catch(TimeoutException expected){checks++;}
            blocker.commit();
            check(((Map<?,?>)result(read)).get("id").equals(FIRST));check(Boolean.TRUE.equals(result(revoke)));
            denied(()->store.detail(TENANT,ADMIN,SCHOOL,"enrollments",FIRST),"EDUCATION_FORBIDDEN");
        } finally {
            // Closing blocker rolls back on failure. Finish any issued work before restoring this fixture role.
            if(read!=null)try{result(read);}catch(Exception ignored){}
            if(revoke!=null)try{result(revoke);}catch(Exception ignored){}
            pool.shutdownNow();pool.awaitTermination(15,TimeUnit.SECONDS);
            if(count(ds,"SELECT COUNT(*) FROM sys_role WHERE role_id=988101 AND tenant_id='000000' AND role_key='edu_roster_ci_988101' AND role_name='Roster tenant admin' AND del_flag='0' AND status='1'")==1)sql(ds,"UPDATE sys_role SET status='0' WHERE role_id=988101 AND tenant_id='000000' AND role_key='edu_roster_ci_988101' AND role_name='Roster tenant admin' AND del_flag='0' AND status='1'");
            check(count(ds,"SELECT COUNT(*) FROM sys_role WHERE role_id=988101 AND tenant_id='000000' AND role_key='edu_roster_ci_988101' AND role_name='Roster tenant admin' AND del_flag='0' AND status='0'")==1);
        }
    }
    public static void main(String[] args)throws Exception {
        if(!"true".equals(System.getenv("GITHUB_ACTIONS")))throw new IllegalStateException("Disposable CI only");
        String database=env("FCP_EDUCATION_TEST_DATABASE");if(!database.matches("fcp_ci_[a-f0-9]{12}"))throw new IllegalStateException("Owned database required");
        RealSource ds=new RealSource(env("FCP_EDUCATION_TEST_URL"),env("FCP_EDUCATION_TEST_USER"),env("FCP_EDUCATION_TEST_PASSWORD"));
        try(var c=ds.getConnection();var q=c.createStatement();var r=q.executeQuery("SELECT DATABASE(),VERSION()")){r.next();check(database.equals(r.getString(1))&&r.getString(2).startsWith("8.4."));}
        String manifest=env("FCP_EDUCATION_TEST_MANIFEST");RosterStore store=new RosterStore(ds,()->Long.toString(IDS.incrementAndGet()),manifest);
        long audits=count(ds,"SELECT COUNT(*) FROM ed_core_audit_event"),outbox=count(ds,"SELECT COUNT(*) FROM ed_core_outbox");
        check(store.capabilities(TENANT,ADMIN).get("schemaVersion").equals("education-roster-v1"));
        check(((Map<?,?>)store.capabilities(TENANT,ADMIN).get("operations")).get("personDisplay").equals(false));
        check(((Map<?,?>)store.capabilities(TENANT,VIEWER).get("operations")).get("assignmentList").equals(true));
        denied(()->store.list(TENANT,VIEWER,SCHOOL,"enrollments",null,10),"EDUCATION_FORBIDDEN");
        denied(()->store.list(TENANT,SCHOOL_ADMIN,SCHOOL,"assignments",null,10),"EDUCATION_FORBIDDEN");
        denied(()->store.capabilities("education-other",ADMIN),"EDUCATION_FORBIDDEN");
        denied(()->store.capabilities(TENANT+" ",ADMIN),"EDUCATION_FORBIDDEN");
        RosterStore wrongManifest=new RosterStore(ds,()->Long.toString(IDS.incrementAndGet()),manifest.equals("a".repeat(64))?"b".repeat(64):"a".repeat(64));
        denied(()->wrongManifest.capabilities(TENANT,ADMIN),"EDUCATION_FORBIDDEN");
        var schoolPage=store.schools(TENANT,SCHOOL_ADMIN,null,1);
        check(items(schoolPage).size()==1&&items(schoolPage).get(0).get("id").equals(SCHOOL)&&schoolPage.get("nextAfterId")==null);
        Map<String,Object> pending=items(store.schools(TENANT,ADMIN,null,100)).stream().filter(row->row.get("readiness").equals("EXECUTION_GRANT_MISSING")).findFirst().orElseThrow();
        check(!Set.of("800001","800002","800003").contains(pending.get("id")));check(pending.get("readiness").equals("EXECUTION_GRANT_MISSING"));check(((Map<?,?>)pending.get("operations")).values().stream().allMatch(Boolean.FALSE::equals));
        for(String kind:List.of("enrollments","memberships","assignments")) {
            var first=store.list(TENANT,ADMIN,SCHOOL,kind,null,1);check(items(first).size()==1&&items(first).get(0).get("id").equals(FIRST)&&first.get("nextAfterId").equals(FIRST));
            var second=store.list(TENANT,ADMIN,SCHOOL,kind,FIRST,1);check(items(second).get(0).get("id").equals(LEFT)&&second.get("nextAfterId").equals(LEFT));
            var third=store.list(TENANT,ADMIN,SCHOOL,kind,LEFT,1);check(items(third).get(0).get("id").equals(LAST)&&third.get("nextAfterId")==null);
            check(items(third).get(0).get("rowVersion").equals(LAST));
            check(items(store.list(TENANT,ADMIN,SCHOOL,kind,LAST,100)).isEmpty());
            for(String id:List.of(FIRST,LEFT,LAST)){var detail=store.detail(TENANT,ADMIN,SCHOOL,kind,id);check(detail.get("id").equals(id));metadata(detail,kind);}
            denied(()->store.detail(TENANT,ADMIN,SCHOOL,kind,OTHER),"EDUCATION_NOT_FOUND");
            denied(()->store.list(TENANT,SCHOOL_ADMIN,"800002",kind,null,10),"EDUCATION_FORBIDDEN");
            denied(()->store.list(TENANT,ADMIN,"800003",kind,null,10),"EDUCATION_FORBIDDEN");
        }
        // The shared natural person does not confer another school's student or teacher records.
        metadata(store.detail(TENANT,ADMIN,"800002","enrollments",OTHER),"enrollments");
        metadata(store.detail(TENANT,ADMIN,"800002","assignments",OTHER),"assignments");
        // Unknown enums fail closed even though the design DDL does not CHECK their values.
        for(String[] mutation:List.of(
            new String[]{"ed_identity_person","880001","status","ACTIVE","UNKNOWN"},
            new String[]{"ed_education_enrollment",FIRST,"status","ACTIVE","UNKNOWN"},
            new String[]{"ed_education_assignment",FIRST,"role_code","TEACHER","UNKNOWN"},
            new String[]{"ed_education_term","810001","status","OPEN","UNKNOWN"})) {
            String table=mutation[0],id=mutation[1],column=mutation[2],before=mutation[3],after=mutation[4];
            String kind=table.equals("ed_education_assignment")?"assignments":table.equals("ed_education_term")?"memberships":"enrollments";
            // These identifiers are private test constants, never HTTP or user-supplied SQL.
            sql(ds,"UPDATE "+table+" SET "+column+"=? WHERE tenant_id=? AND id=CAST(? AS UNSIGNED) AND "+column+"=?",after,TENANT,id,before);
            try{denied(()->store.detail(TENANT,ADMIN,SCHOOL,kind,FIRST),"EDUCATION_INVALID_RELATION");}
            finally{sql(ds,"UPDATE "+table+" SET "+column+"=? WHERE tenant_id=? AND id=CAST(? AS UNSIGNED) AND "+column+"=?",before,TENANT,id,after);}
        }
        // Same numeric identity exists only in the other tenant; owner resolution must not cross it.
        check(count(ds,"SELECT COUNT(*) FROM ed_identity_person WHERE tenant_id='education-other' AND id=880099")==1);
        check(count(ds,"SELECT COUNT(*) FROM ed_identity_person WHERE tenant_id='000000' AND id=880099")==0);
        sql(ds,"UPDATE ed_education_enrollment SET person_id=880099 WHERE tenant_id=? AND id=CAST(? AS UNSIGNED) AND person_id=880001",TENANT,FIRST);
        try{denied(()->store.detail(TENANT,ADMIN,SCHOOL,"enrollments",FIRST),"EDUCATION_INVALID_RELATION");}
        finally{sql(ds,"UPDATE ed_education_enrollment SET person_id=880001 WHERE tenant_id=? AND id=CAST(? AS UNSIGNED) AND person_id=880099",TENANT,FIRST);}
        sql(ds,"UPDATE ed_education_assignment SET class_id=890002 WHERE tenant_id=? AND id=CAST(? AS UNSIGNED) AND class_id=890001",TENANT,FIRST);
        try {
            denied(()->store.detail(TENANT,ADMIN,SCHOOL,"assignments",FIRST),"EDUCATION_NOT_FOUND");
            check(store.detail(TENANT,ADMIN,"800002","assignments",FIRST).get("schoolId").equals("800002"));
        } finally {sql(ds,"UPDATE ed_education_assignment SET class_id=890001 WHERE tenant_id=? AND id=CAST(? AS UNSIGNED) AND class_id=890002",TENANT,FIRST);}
        sql(ds,"UPDATE ed_education_term SET status='FROZEN' WHERE tenant_id=? AND id=810001 AND status='OPEN'",TENANT);
        try{check(store.detail(TENANT,ADMIN,SCHOOL,"memberships",FIRST).get("termStatus").equals("FROZEN"));}
        finally{sql(ds,"UPDATE ed_education_term SET status='OPEN' WHERE tenant_id=? AND id=810001 AND status='FROZEN'",TENANT);}
        // Removing the exact teacher menu cannot be replaced by student:read, legacy permissions or view.
        sql(ds,"UPDATE sys_menu SET status='1' WHERE menu_id=986102 AND parent_id=986120 AND perms='education:roster:teacher:read' AND status='0'");
        try{check(items(store.list(TENANT,ADMIN,SCHOOL,"enrollments",null,10)).size()==3);denied(()->store.list(TENANT,ADMIN,SCHOOL,"assignments",null,10),"EDUCATION_FORBIDDEN");}
        finally{sql(ds,"UPDATE sys_menu SET status='0' WHERE menu_id=986102 AND parent_id=986120 AND perms='education:roster:teacher:read' AND status='1'");check(count(ds,"SELECT COUNT(*) FROM sys_menu WHERE menu_id=986102 AND parent_id=986120 AND perms='education:roster:teacher:read' AND status='0'")==1);}
        sql(ds,"UPDATE sys_menu SET status='1' WHERE menu_id=986101 AND parent_id=986120 AND perms='education:roster:student:read' AND status='0'");
        try{check(items(store.list(TENANT,ADMIN,SCHOOL,"assignments",null,10)).size()==3);denied(()->store.list(TENANT,ADMIN,SCHOOL,"enrollments",null,10),"EDUCATION_FORBIDDEN");}
        finally{sql(ds,"UPDATE sys_menu SET status='0' WHERE menu_id=986101 AND parent_id=986120 AND perms='education:roster:student:read' AND status='1'");check(count(ds,"SELECT COUNT(*) FROM sys_menu WHERE menu_id=986101 AND parent_id=986120 AND perms='education:roster:student:read' AND status='0'")==1);}
        admittedReadBlocksNativeRevocation(ds,store);
        check(store.securityEventFailures()==0);
        check(audits==count(ds,"SELECT COUNT(*) FROM ed_core_audit_event")&&outbox==count(ds,"SELECT COUNT(*) FROM ed_core_outbox"));
        System.out.println("{\"status\":\"PASS\",\"scope\":\"REAL_MYSQL_ROSTER_JDBC_NOT_HTTP_LOGIN\",\"checks\":"+checks+",\"personDisplayPolicy\":\"PENDING\",\"writes\":\"NOT_EXPOSED\"}");
    }
}
