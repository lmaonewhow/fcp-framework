import fcp.*;
import dev.fcp.dual.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.lang.reflect.*;
import java.sql.*;
import javax.sql.DataSource;
import static fcp.Schema.*;

/** New bug-specific probes; can run unchanged against the 0.3.1 sources. */
public final class AuditRegression {
    interface Checked { void run() throws Exception; }
    static final List<Object> results = new ArrayList<>();
    static void test(String name, Checked body) {
        try { body.run(); results.add(Json.obj("name",name,"status","PASS")); }
        catch (Throwable e) { results.add(Json.obj("name",name,"status","FAIL","error",e.toString())); }
    }
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    static void invalid(Runnable body) {
        try { body.run(); } catch (IllegalArgumentException | FcpException good) { return; }
        throw new AssertionError("Invalid input unexpectedly accepted");
    }
    static void denied(String code, Runnable body) {
        try { body.run(); } catch (FcpException e) { check(e.code.equals(code),"Expected " + code+", got "+e.code); return; }
        throw new AssertionError("Expected rejection: "+code);
    }
    static Engine kernel(AtomicReference<Engine.Context> saved) {
        Registry r = new Registry();
        r.register("contract",Json.obj("id","test.v1","operations",Json.obj("run",Json.obj("input",Json.obj("type","object"),"output",Map.of(),"effect","write"))));
        r.register("module",Json.obj("protocol","fcp/0.3","kind","module","id","one","version","1","provides",Json.obj("api","test.v1"),"runtime",Json.obj("provider","one")));
        r.factories.put("one",(m,s)->(c,e,o,i)->{saved.set(c);return true;});
        Engine engine = new Engine(r,null);
        engine.publish("test",Json.obj("protocol","fcp/0.3","kind","composition","id","test.app","modules",List.of("one"),"exports",Json.obj("one","one/api")),0);
        return engine;
    }
    // JDBC fault injection tests connection lifecycle, not database SQL correctness.
    static final class FaultDb implements InvocationHandler {
        final String fault;
        boolean auto = true, aborted, closed, rollbackFailed, attemptedCommit;
        int resetsAfterFailedRollback, rollbacks;
        FaultDb(String fault) { this.fault=fault; }
        static Object zero(Class<?> t) { if(!t.isPrimitive()) return null; if(t==boolean.class)return false; if(t==int.class)return 0; if(t==long.class)return 0L; return null; }
        <T> T proxy(Class<T> t, InvocationHandler h) { return t.cast(Proxy.newProxyInstance(t.getClassLoader(),new Class<?>[]{t},h)); }
        DataSource source() { return proxy(DataSource.class,(p,m,a)->m.getName().equals("getConnection")?proxy(Connection.class,this):zero(m.getReturnType())); }
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch(method.getName()) {
                case "getAutoCommit" -> auto;
                case "setAutoCommit" -> {boolean next=(Boolean)args[0];if(next && rollbackFailed)resetsAfterFailedRollback++;auto=next;yield null;}
                case "isClosed" -> closed;
                case "abort" -> {aborted=true;closed=true;yield null;}
                case "close" -> {closed=true;yield null;}
                case "rollback" -> {rollbacks++;if(fault.equals("rollback")){rollbackFailed=true;throw new SQLException("injected rollback loss","08006");}yield null;}
                case "commit" -> {attemptedCommit=true;if(fault.equals("commit"))throw new SQLException("commit acknowledgement lost","08006");yield null;}
                case "prepareStatement" -> {
                    String sql=(String)args[0];
                    yield proxy(PreparedStatement.class,(p,m,a)->{
                        if(m.getName().equals("executeQuery"))return proxy(ResultSet.class,(rp,rm,ra)->zero(rm.getReturnType()));
                        if(m.getName().equals("executeUpdate")){
                            if(sql.startsWith("INSERT INTO fcp_dual_audit") && (fault.equals("rollback") || fault.equals("statement")))throw new SQLException("injected audit failure","23000");
                            return 1;
                        }
                        return zero(m.getReturnType());
                    });
                }
                default -> zero(method.getReturnType());
            };
        }
        void commit() { new JdbcRecordStore(source()).commit("tenant-a","purchase","101","request-123", "a".repeat(64),"id-1",0,Json.obj("title","a"),Map.of()); }
    }
    public static void main(String[] args) throws Exception {
        Path runtime=Path.of(args[0]).toAbsolutePath().normalize();
        test("json_decimal_not_rounded_through_double",()->check(Json.parse("0.123456789012345678901").equals(new BigDecimal("0.123456789012345678901")),"decimal changed"));
        test("json_small_exponent_not_silently_zero",()->check(((BigDecimal)Json.parse("1e-400")).signum()==1,"underflow became zero"));
        test("json_rejects_non_hex_unicode_escape",()->invalid(()->Json.parse("\"\\u+041\"")));
        test("writer_rejects_nan",()->invalid(()->Json.write(Double.NaN)));
        test("writer_rejects_unpaired_surrogate",()->invalid(()->Json.write("\uD800")));
        test("writer_rejects_unsafe_integer",()->invalid(()->Json.write(new BigInteger("9007199254740992"))));
        test("writer_bounds_nesting",()->{Object v=0;for(int n=0;n<35;n++)v=List.of(v);final Object deep=v;invalid(()->Json.write(deep));});
        test("json_bounds_zero_exponent",()->invalid(()->Json.parse("0e-2147483647")));
        test("enum_numeric_value_semantics",()->Schema.validate(new BigDecimal("1.0"),Json.obj("type","integer","enum",List.of(new BigDecimal("1")))));
        test("schema_rejects_invalid_bound_type",()->invalid(()->Schema.check(Json.obj("type","string","minLength","1"))));
        test("uuid_shape_is_canonical",()->invalid(()->HostRequest.parse(Json.write(Json.obj("instance","-".repeat(36),"generation",1,"input",Map.of())),false)));
        test("expired_context_cannot_commit",()->{AtomicReference<Engine.Context> saved=new AtomicReference<>();try(Engine e=kernel(saved)){e.invoke("test","one","run",Map.of(),Map.of(),1L);denied("CONTEXT_CLOSED",()->saved.get().atomic(()->"late write"));}});
        test("java_title_limit_uses_unicode_codepoints",()->{String title="\uD83D\uDE80".repeat(100);Map<String,Object> result=map(new PurchaseRules().invoke(null,"api","plan",Json.obj("action","create","actor","101","record",Map.of(),"data",Json.obj("title",title,"amount","1.00"))));check(obj(result,"body").get("title").equals(title),"valid Unicode title rejected");});
        test("jdbc_failed_rollback_never_enables_autocommit",()->{FaultDb db=new FaultDb("rollback");try{db.commit();}catch(FcpException expected){}check(db.rollbackFailed,"fault not reached");check(db.resetsAfterFailedRollback==0,"automatic commit after rollback failure");check(db.aborted,"connection was not invalidated");});
        test("jdbc_commit_ack_loss_is_explicit_unknown",()->{FaultDb db=new FaultDb("commit");denied("COMMIT_UNKNOWN",db::commit);check(db.attemptedCommit&&db.aborted,"unknown connection not quarantined");});
        test("jdbc_normal_rollback_restores_clean_connection",()->{FaultDb db=new FaultDb("statement");denied("STORAGE",db::commit);check(db.rollbacks==1 && db.auto && !db.attemptedCommit,"wrong rollback boundary");});
        test("replay_cannot_bypass_current_row_visibility",()->{
            Path file=Files.createTempDirectory("fcp-audit-").resolve("state.json");RecordStore store=new FileRecordStore(file);
            try(Engine e=DualSystem.create(runtime,store,(c,t,o)->{})){
                e.publish("tenant-a","ruoyi.dual",0);
                Map<String,Object> owner=Json.obj("subject","101","viewAll",false),manager=Json.obj("subject","201","viewAll",true);
                Map<String,Object> r=map(e.invoke("tenant-a","repair","create",Json.obj("requestKey","audit-create","data",Json.obj("title","损坏","description","待修","severity","normal")),owner,1L));
                Map<String,Object> input=Json.obj("id",r.get("id"),"expectedVersion",1,"requestKey","audit-assign","data",Json.obj("assignee","301"));
                e.invoke("tenant-a","repair","assign",input,manager,1L);
                denied("FORBIDDEN",()->e.invoke("tenant-a","repair","assign",input,Json.obj("subject","201","viewAll",false),1L));
            }
        });
        long failed=results.stream().filter(v->map(v).get("status").equals("FAIL")).count();
        System.out.println(Json.write(Json.obj("suite","audit-032","scope","real JVM/Python plus JDBC fault-injection; not real SQL driver or RuoYi","passed",results.size()-failed,"failed",failed,"tests",results)));
        if(failed>0)System.exit(1);
    }
}
