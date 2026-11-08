package fcp.education;

import fcp.FcpException;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Read-only school roster metadata. Caller identity must come from the native adapter.
 * No arbitrary CRUD, caller-supplied permissions, legacy scopes, or default authority rows.
 */
public final class RosterStore {
    private static final String CAP="education.roster", VERSION="education-roster-v1";
    private static final String ORG="ed_education_org_unit", CLS="ed_education_class", TERM="ed_education_term";
    private final DataSource source;
    private final Supplier<String> ids;
    private final String manifest;
    private final Semaphore securityBudget=new Semaphore(4);
    private final LongAdder securityFailures=new LongAdder();
    public RosterStore(DataSource source,Supplier<String> ids,String manifestDigest) {
        this.source=Objects.requireNonNull(source);this.ids=Objects.requireNonNull(ids);
        if(manifestDigest==null||!manifestDigest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Trusted manifest SHA-256 required");
        this.manifest=manifestDigest;
    }
    public long securityEventFailures(){return securityFailures.sum();}
    public void rejectBoundary(String tenant,String actor){security(tenant,actor,null,"EDUCATION_INVALID_INPUT","DENIED");}
    public void rejectIdentity(String tenant,String actor){security(tenant,actor,null,"EDUCATION_FORBIDDEN","DENIED");}
    public void boundaryAuditUnavailable(){securityFailures.increment();}
    private static FcpException fail(String code){return new FcpException(code,"Education roster request rejected");}
    private static String id(Object v){return EducationScalars.unsignedBigint(v,true);}
    private static String str(Object v,int n){return EducationScalars.text(v,n);}
    private String nextId(){return id(ids.get());}
    private static String now(){return Instant.now().truncatedTo(ChronoUnit.MICROS).toString();}
    private static Timestamp timestamp(String value){EducationScalars.utcTimestamp(value);return Timestamp.from(Instant.parse(value));}
    private static String dateTime(ResultSet r,int index)throws SQLException {Timestamp t=r.getTimestamp(index,Calendar.getInstance(TimeZone.getTimeZone("UTC")));return t==null?null:t.toInstant().toString();}
    private static String textId(ResultSet r,int index)throws SQLException {String v=r.getString(index);return v==null?null:EducationScalars.unsignedBigint(v,false);}
    private static Map<String,Object> map(Object...pairs){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    private static PreparedStatement statement(Connection c,String sql,Object...values)throws SQLException {
        PreparedStatement q=c.prepareStatement(sql);q.setQueryTimeout(10);
        for(int i=0;i<values.length;i++) {Object v=values[i];if(v instanceof Timestamp t)q.setTimestamp(i+1,t,Calendar.getInstance(TimeZone.getTimeZone("UTC")));else q.setObject(i+1,v);}
        return q;
    }
    private static int update(Connection c,String sql,Object...args)throws SQLException {try(var q=statement(c,sql,args)){return q.executeUpdate();}}
    private static void single(Connection c,String sql,Object...args)throws SQLException {if(update(c,sql,args)!=1)throw fail("EDUCATION_CONFLICT");}
    private static void required(Connection c,String sql,Object...args)throws SQLException {try(var q=statement(c,sql,args);var r=q.executeQuery()){if(!r.next())throw fail("EDUCATION_FORBIDDEN");}}
    private Connection connection()throws SQLException {
        Connection c=source.getConnection();boolean good=false;
        try {
            if(!c.getAutoCommit())throw fail("EDUCATION_AUTHORITY_UNAVAILABLE");
            try(var q=c.createStatement()){q.execute("SET SESSION time_zone='+00:00'");}
            try(var q=c.createStatement();var r=q.executeQuery("SELECT @@session.time_zone,@@session.sql_mode,@@session.foreign_key_checks")) {
                if(!r.next()||!"+00:00".equals(r.getString(1))||!(Arrays.asList(r.getString(2).split(",")).contains("STRICT_TRANS_TABLES")||Arrays.asList(r.getString(2).split(",")).contains("STRICT_ALL_TABLES"))||r.getInt(3)!=1)throw fail("EDUCATION_SESSION_INVALID");
            }
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);if(c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw fail("EDUCATION_SESSION_INVALID");c.setAutoCommit(false);good=true;return c;
        } finally {if(!good)c.close();}
    }
    private record Grant(String role,String kind,String school,String operation){}
    private record Auth(String tenant,String actor,List<Grant> grants) {
        boolean permits(String school,String operation){return grants.stream().anyMatch(g->g.operation.equals(operation)&&(g.kind.equals("TENANT_ADMIN")||g.school.equals(school)));}
        boolean tenant(String operation){return grants.stream().anyMatch(g->g.operation.equals(operation)&&g.kind.equals("TENANT_ADMIN"));}
    }
    /** Locks admission state before native membership. Native UPDATE/DELETE of selected rows
     * must wait for the same InnoDB row locks, even if the old native service has no epoch hook.
     * No old ticket is accepted: every request freshly locks all authoritative rows.
     */
    private Auth authorize(Connection c,String tenant,String actor)throws SQLException {
        EducationScalars.tenantId(tenant);str(actor,100);
        for(String key:List.of("policy:tenant","cap:education.roster"))required(c,"SELECT epoch FROM ed_core_write_fence WHERE tenant_id=? AND fence_key=? AND enabled=1 FOR SHARE",tenant,key);
        required(c,"SELECT epoch FROM ed_core_access_epoch WHERE tenant_id=? AND CAST(subject_key AS BINARY)=CAST(? AS BINARY) AND enabled=1 FOR SHARE",tenant,actor);
        execution(c,tenant,"tenant");
        required(c,"SELECT tenant_id FROM ed_core_tenant_binding WHERE tenant_id=? AND CAST(host_tenant_key AS BINARY)=CAST(? AS BINARY) AND host_kind='ruoyi-vue-plus' AND status='ACTIVE' FOR SHARE",tenant,tenant);
        // Binary comparison explicitly defeats PAD SPACE/case-insensitive host-column equality.
        nativeExact(c,tenant,null,"SELECT tenant_id FROM sys_tenant WHERE CAST(tenant_id AS BINARY)=CAST(? AS BINARY) AND status='0' AND del_flag='0' AND (expire_time IS NULL OR expire_time>UTC_TIMESTAMP()) FOR SHARE",tenant);
        nativeExact(c,tenant,actor,"SELECT tenant_id,CAST(user_id AS CHAR) FROM sys_user WHERE CAST(tenant_id AS BINARY)=CAST(? AS BINARY) AND CAST(CAST(user_id AS CHAR) AS BINARY)=CAST(? AS BINARY) AND status='0' AND del_flag='0' FOR SHARE",tenant,actor);
        List<Grant> grants=new ArrayList<>();
        String sql="SELECT CAST(r.role_id AS CHAR),g.admin_kind,CAST(g.school_id AS CHAR),g.operation_key,r.tenant_id,t.tenant_id "
            +"FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id "
            +"JOIN ed_host_roster_role g ON g.native_role_id=r.role_id AND CAST(r.tenant_id AS BINARY)=CAST(g.tenant_id AS BINARY) "
            +"JOIN sys_role_menu rm ON rm.role_id=r.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id "
            +"JOIN sys_tenant t ON CAST(t.tenant_id AS BINARY)=CAST(g.tenant_id AS BINARY) "
            +"LEFT JOIN sys_tenant_package p ON p.package_id=t.package_id "
            +"WHERE g.tenant_id=? AND CAST(CAST(ur.user_id AS CHAR) AS BINARY)=CAST(? AS BINARY) "
            +"AND r.status='0' AND r.del_flag='0' AND g.enabled=1 AND g.purpose_key='school-roster-metadata-v1' AND m.status='0' "
            +"AND CAST(m.perms AS BINARY)=CAST(CONCAT('education:roster:',g.operation_key) AS BINARY) "
            +"AND (CAST(t.tenant_id AS BINARY)=CAST('000000' AS BINARY) OR "
            +"(p.status='0' AND p.del_flag='0' AND FIND_IN_SET(CAST(m.menu_id AS CHAR),p.menu_ids)>0)) "
            +"ORDER BY r.role_id,g.school_id,g.operation_key,m.menu_id LIMIT 257 FOR SHARE";
        try(var q=statement(c,sql,tenant,actor);var r=q.executeQuery()) {
            while(r.next()){if(!tenant.equals(r.getString(5))||!tenant.equals(r.getString(6)))throw fail("EDUCATION_FORBIDDEN");if(grants.size()==256)throw fail("EDUCATION_RESOURCE_LIMIT");String kind=r.getString(2),school=r.getString(3),operation=r.getString(4);validateGrant(kind,school,operation);grants.add(new Grant(r.getString(1),kind,school,operation));}
        }
        if(grants.isEmpty())throw fail("EDUCATION_FORBIDDEN");
        return new Auth(tenant,actor,List.copyOf(grants));
    }
    private static void nativeExact(Connection c,String tenant,String actor,String sql,Object...args)throws SQLException {
        try(var q=statement(c,sql,args);var r=q.executeQuery()) {
            if(!r.next()||!tenant.equals(r.getString(1))||(actor!=null&&!actor.equals(r.getString(2))))throw fail("EDUCATION_FORBIDDEN");
        }
    }
    private void execution(Connection c,String tenant,String scope)throws SQLException {required(c,"SELECT epoch FROM ed_core_execution_grant WHERE tenant_id=? AND scope_key=? AND capability_key=? AND manifest_digest=? AND enabled=1 FOR SHARE",tenant,scope,CAP,manifest);}
    private static String schoolScopeSql(int schoolCount,boolean tenantWide) {
        if(tenantWide)return "";
        if(schoolCount<1||schoolCount>256)throw fail("EDUCATION_FORBIDDEN");
        return " AND id IN ("+String.join(",",Collections.nCopies(schoolCount,"CAST(? AS UNSIGNED)"))+")";
    }
    private void security(String tenant,String actor,String intent,String code,String outcome) {
        if(!securityBudget.tryAcquire()){securityFailures.increment();return;}
        try(Connection c=connection()) {
            EducationScalars.tenantId(tenant);str(actor,100);String at=now();
            single(c,"INSERT INTO ed_core_security_event(tenant_id,id,actor_key,operation_id,request_attempt_id,event_code,outcome,safe_context_json,occurred_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",tenant,nextId(),actor,intent,UUID.randomUUID().toString(),code,outcome,"{}",timestamp(at),timestamp(at));c.commit();
        }catch(Exception unavailable){securityFailures.increment();}finally{securityBudget.release();}
    }
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
    private <T>T transaction(String tenant,String actor,Work<T> work) {
        try(Connection c=connection()) {
            try {T value=work.run(c);c.commit();return value;}
            catch(RuntimeException|SQLException failed){try{c.rollback();}catch(SQLException ignored){}throw failed;}
        } catch(SQLException failed) {
            String code=Set.of(1205,1213).contains(failed.getErrorCode())?"EDUCATION_RETRY_READ":"EDUCATION_AUTHORITY_UNAVAILABLE";
            security(tenant,actor,null,code,"FAILED");throw fail(code);
        } catch(RuntimeException failed) {
            String code=failed instanceof FcpException f?f.code:failed instanceof IllegalArgumentException?"EDUCATION_INVALID_INPUT":"EDUCATION_AUTHORITY_UNAVAILABLE";
            security(tenant,actor,null,code,failed instanceof FcpException||failed instanceof IllegalArgumentException?"DENIED":"FAILED");throw fail(code);
        }
    }
    /** Unknown roles/operations are not an implied authorization. */
    static void validateGrant(String kind,String school,String operation) {
        EducationScalars.unsignedBigint(school,false);
        if(!Set.of("student:read","teacher:read").contains(operation)
            ||!("TENANT_ADMIN".equals(kind)&&"0".equals(school)||"SCHOOL_ADMIN".equals(kind)&&!"0".equals(school)))throw fail("EDUCATION_FORBIDDEN");
    }
    static String operation(String kind) {
        return switch(kind){case "enrollments","memberships"->"student:read";case "assignments"->"teacher:read";default->throw fail("EDUCATION_INVALID_INPUT");};
    }
    public record Page(String afterId,int limit) {}
    /** Called by the HTTP adapter before it can discard duplicate parameters. */
    public static Page page(Map<String,? extends List<String>> parameters,boolean paged) {
        if(parameters==null||!(paged?Set.of("afterId","limit"):Set.of()).containsAll(parameters.keySet()))throw fail("EDUCATION_INVALID_INPUT");
        for(var values:parameters.values())if(values==null||values.size()!=1||values.get(0)==null)throw fail("EDUCATION_INVALID_INPUT");
        String after=parameters.containsKey("afterId")?parameters.get("afterId").get(0):"0";
        EducationScalars.unsignedBigint(after,false);
        String raw=parameters.containsKey("limit")?parameters.get("limit").get(0):"50";
        if(!raw.matches("[1-9][0-9]{0,2}"))throw fail("EDUCATION_INVALID_INPUT");
        int limit=Integer.parseInt(raw);if(limit>100)throw fail("EDUCATION_INVALID_INPUT");
        return new Page(after,limit);
    }
    private static Map<String,Object> operations(Auth a,String school) {
        boolean student=a!=null&&a.permits(school,"student:read"),teacher=a!=null&&a.permits(school,"teacher:read");
        return map("schoolList",student||teacher,"enrollmentList",student,"enrollmentDetail",student,
            "membershipList",student,"membershipDetail",student,"assignmentList",teacher,"assignmentDetail",teacher,
            "personDisplay",false,"enrollmentWrite",false,"membershipWrite",false,"assignmentWrite",false,"personRename",false,"termWrite",false);
    }
    public static Map<String,Object> blocked() {
        return map("schemaVersion",VERSION,"status","BLOCKED","operations",operations(null,null),
            "readiness",map("schoolAuthorization","NOT_ENABLED","transactionProtocol","NOT_ENABLED","nativeAcceptance","NOT_RUN","personDisplayPolicy","PENDING"),
            "blockers",List.of("ROSTER_NOT_ENABLED","PERSON_DISPLAY_POLICY_PENDING","ROSTER_WRITE_POLICY_PENDING"),"securityEventFailures",0L);
    }
    public Map<String,Object> capabilities(String tenant,String actor) {
        return transaction(tenant,actor,c->{Auth a=authorize(c,tenant,actor);Map<String,Object> ops=operations(null,null);
            for(Grant grant:a.grants)for(var entry:operations(a,grant.school).entrySet())ops.merge(entry.getKey(),entry.getValue(),(x,y)->Boolean.TRUE.equals(x)||Boolean.TRUE.equals(y));
            return map("schemaVersion",VERSION,"status","READY","operations",ops,
                "readiness",map("schoolAuthorization","IMPLEMENTED","transactionProtocol","IMPLEMENTED","nativeAcceptance","NOT_RUN","personDisplayPolicy","PENDING"),
                "blockers",List.of("PERSON_DISPLAY_POLICY_PENDING","ROSTER_WRITE_POLICY_PENDING"),"securityEventFailures",securityEventFailures());});
    }
    private boolean executionAvailable(Connection c,String tenant,String school)throws SQLException {
        try(var q=statement(c,"SELECT epoch FROM ed_core_execution_grant WHERE tenant_id=? AND scope_key=? AND capability_key=? AND manifest_digest=? AND enabled=1 FOR SHARE",tenant,"school:"+school,CAP,manifest);var r=q.executeQuery()){return r.next();}
    }
    public Map<String,Object> schools(String tenant,String actor,String afterId,int limit) {
        return transaction(tenant,actor,c->{Auth a=authorize(c,tenant,actor);String after=EducationScalars.unsignedBigint(afterId==null?"0":afterId,false);checkLimit(limit);
            boolean all=a.tenant("student:read")||a.tenant("teacher:read");
            List<String> schools=a.grants.stream().filter(g->g.kind.equals("SCHOOL_ADMIN")).map(g->id(g.school)).distinct().sorted().toList();
            String at=now();List<Object> values=new ArrayList<>(List.of(tenant,after,timestamp(at),timestamp(at)));if(!all)values.addAll(schools);values.add(limit+1);
            List<Map<String,Object>> items=new ArrayList<>();
            // Explicit grants restrict the SQL before keyset pagination. No post-page school filtering.
            try(var q=statement(c,"SELECT id,name,row_version FROM "+ORG+" WHERE tenant_id=? AND id>CAST(? AS UNSIGNED) AND org_kind='SCHOOL' AND parent_id IS NULL AND valid_from<=? AND (valid_to IS NULL OR valid_to>?)"+schoolScopeSql(schools.size(),all)+" ORDER BY id LIMIT ?",values.toArray());var r=q.executeQuery()) {
                while(r.next())items.add(map("id",textId(r,1),"name",r.getString(2),"rowVersion",textId(r,3)));
            }
            // Directory metadata is deliberately visible while this module's school grant is pending.
            // No roster data is read until school grant and school row are locked in order.
            Map<String,Boolean> readiness=new HashMap<>();
            for(var row:items){String school=(String)row.get("id");readiness.put(school,executionAvailable(c,tenant,school));}
            List<Map<String,Object>> locked=new ArrayList<>();
            for(var hint:items){String school=(String)hint.get("id");
                try(var q=statement(c,"SELECT id,name,row_version FROM "+ORG+" WHERE tenant_id=? AND id=CAST(? AS UNSIGNED) AND org_kind='SCHOOL' AND parent_id IS NULL AND valid_from<=? AND (valid_to IS NULL OR valid_to>?) FOR SHARE",tenant,school,timestamp(at),timestamp(at));var r=q.executeQuery()) {
                    if(!r.next())throw fail("EDUCATION_RETRY_READ"); // A changed directory is never served from the unlocked hint.
                    boolean ready=readiness.get(school);locked.add(map("id",textId(r,1),"name",r.getString(2),"rowVersion",textId(r,3),"operations",operations(ready?a:null,school),"readiness",ready?"READY":"EXECUTION_GRANT_MISSING"));
                }
            }
            return pageResult(locked,limit);});
    }
    private static void checkLimit(int limit){if(limit<1||limit>100)throw fail("EDUCATION_INVALID_INPUT");}
    private static Map<String,Object> pageResult(List<Map<String,Object>> items,int limit) {
        boolean more=items.size()>limit;if(more)items.remove(items.size()-1);
        return map("items",items,"nextAfterId",more?items.get(items.size()-1).get("id"):null);
    }
    private void school(Connection c,Auth a,String school,String operation,String at)throws SQLException {
        id(school);if(!a.permits(school,operation))throw fail("EDUCATION_FORBIDDEN");
        execution(c,a.tenant,"school:"+school);
        required(c,"SELECT id FROM "+ORG+" WHERE tenant_id=? AND id=CAST(? AS UNSIGNED) AND org_kind='SCHOOL' AND parent_id IS NULL AND valid_from<=? AND (valid_to IS NULL OR valid_to>?) FOR SHARE",a.tenant,school,timestamp(at),timestamp(at));
    }
    public Map<String,Object> list(String tenant,String actor,String school,String kind,String afterId,int limit) {
        return transaction(tenant,actor,c->{String op=operation(kind);id(school);String after=EducationScalars.unsignedBigint(afterId==null?"0":afterId,false);checkLimit(limit);
            Auth a=authorize(c,tenant,actor);String at=now();school(c,a,school,op,at);
            List<Map<String,Object>> items=read(c,a,school,kind,after,limit+1,false,at);return pageResult(items,limit);});
    }
    public Map<String,Object> detail(String tenant,String actor,String school,String kind,String object) {
        return transaction(tenant,actor,c->{String op=operation(kind);id(school);id(object);Auth a=authorize(c,tenant,actor);String at=now();school(c,a,school,op,at);
            List<Map<String,Object>> items=read(c,a,school,kind,object,1,true,at);if(items.isEmpty())throw fail("EDUCATION_NOT_FOUND");return items.get(0);});
    }
    /** Only constants from the closed kind switch become identifiers. Values remain bound.
     * Every numeric comparison casts a prevalidated decimal-string parameter to UNSIGNED.
     * Do not rely on Connector/J prepare-mode inference or MySQL string-to-DOUBLE comparison. */
    static String query(String kind,boolean detail) {
        String comparison=detail?"=CAST(? AS UNSIGNED)":">CAST(? AS UNSIGNED)";
        return switch(kind) {
            case "enrollments"->"SELECT e.id,e.school_id,e.valid_from,e.valid_to,e.created_at,e.row_version,e.admission_key,e.status,e.person_id FROM ed_education_enrollment e WHERE e.tenant_id=? AND e.school_id=CAST(? AS UNSIGNED) AND e.id"+comparison+" ORDER BY e.id LIMIT ? FOR SHARE";
            case "memberships"->"SELECT m.id,m.school_id,m.valid_from,m.valid_to,m.created_at,m.row_version,m.enrollment_id,m.class_id FROM ed_education_membership m WHERE m.tenant_id=? AND m.school_id=CAST(? AS UNSIGNED) AND m.id"+comparison+" ORDER BY m.id LIMIT ? FOR SHARE";
            case "assignments"->"SELECT a.id,c.school_id,a.valid_from,a.valid_to,a.created_at,a.row_version,a.class_id,a.role_code,a.person_id FROM ed_education_assignment a JOIN ed_education_class c ON c.tenant_id=a.tenant_id AND c.id=a.class_id WHERE a.tenant_id=? AND c.school_id=CAST(? AS UNSIGNED) AND a.id"+comparison+" ORDER BY a.id LIMIT ? FOR SHARE";
            default->throw fail("EDUCATION_INVALID_INPUT");
        };
    }
    private record Relation(Map<String,Object> row,String person,String enrollment,String klass) {}
    private List<Map<String,Object>> read(Connection c,Auth auth,String school,String kind,String cursor,int limit,boolean detail,String at)throws SQLException {
        List<Relation> raw=new ArrayList<>();
        try(var q=statement(c,query(kind,detail),auth.tenant,school,cursor,limit);var r=q.executeQuery()) {
            while(r.next()) {
                Map<String,Object> row=map("id",textId(r,1),"schoolId",textId(r,2),"validFrom",dateTime(r,3),"validTo",dateTime(r,4),"createdAt",dateTime(r,5),"rowVersion",textId(r,6));
                String person=null,enrollment=null,klass=null;
                if(kind.equals("enrollments")){row.put("admissionKey",r.getString(7));String status=r.getString(8);known(status,Set.of("ACTIVE","TRANSFERRED","GRADUATED","SUSPENDED"));row.put("status",status);person=textId(r,9);}
                else if(kind.equals("memberships")){enrollment=textId(r,7);klass=textId(r,8);row.put("enrollmentId",enrollment);row.put("classId",klass);}
                else{klass=textId(r,7);row.put("classId",klass);String role=r.getString(8);known(role,Set.of("TEACHER","SUBSTITUTE","HEAD"));row.put("roleCode",role);person=textId(r,9);}
                raw.add(new Relation(row,person,enrollment,klass));
            }
        }
        List<Map<String,Object>> items=new ArrayList<>();
        for(Relation relation:raw) {
            var row=relation.row;String from=(String)row.get("validFrom"),to=(String)row.get("validTo");RosterPersonResolver.validateInterval(from,to);
            String person=relation.person;
            if(relation.enrollment!=null) {
                // Owning education relation is resolved under the same transaction, not a global person lookup.
                try(var q=statement(c,"SELECT person_id,valid_from,valid_to,status FROM ed_education_enrollment WHERE tenant_id=? AND school_id=CAST(? AS UNSIGNED) AND id=CAST(? AS UNSIGNED) FOR SHARE",auth.tenant,school,relation.enrollment);var r=q.executeQuery()) {
                    if(!r.next())throw fail("EDUCATION_INVALID_RELATION");person=textId(r,1);String outerFrom=dateTime(r,2),outerTo=dateTime(r,3);known(r.getString(4),Set.of("ACTIVE","TRANSFERRED","GRADUATED","SUSPENDED"));
                    RosterPersonResolver.validateWithin(from,to,outerFrom,outerTo);
                }
            }
            if(relation.klass!=null)classContext(c,auth.tenant,school,relation.klass,row);
            // Do not infer visibility from ACTIVE/LEFT/RESTRICTED. All person display remains PENDING.
            RosterPersonResolver.verify(c,auth.tenant,person,school,kind,from,to,at,auth.permits(school,operation(kind)));
            row.put("personDisplay",map("status","PENDING","reason","PERSON_DISPLAY_POLICY_PENDING"));items.add(row);
        }
        return items;
    }
    private static void classContext(Connection c,String tenant,String school,String klass,Map<String,Object> row)throws SQLException {
        String term;
        try(var q=statement(c,"SELECT term_id,name FROM "+CLS+" WHERE tenant_id=? AND school_id=CAST(? AS UNSIGNED) AND id=CAST(? AS UNSIGNED) FOR SHARE",tenant,school,klass);var r=q.executeQuery()) {
            if(!r.next())throw fail("EDUCATION_INVALID_RELATION");term=textId(r,1);row.put("termId",term);row.put("className",r.getString(2));
        }
        try(var q=statement(c,"SELECT term_code,status FROM "+TERM+" WHERE tenant_id=? AND school_id=CAST(? AS UNSIGNED) AND id=CAST(? AS UNSIGNED) FOR SHARE",tenant,school,term);var r=q.executeQuery()) {
            if(!r.next())throw fail("EDUCATION_INVALID_RELATION");String status=r.getString(2);known(status,Set.of("DRAFT","OPEN","FROZEN","ARCHIVED"));row.put("termCode",r.getString(1));row.put("termStatus",status);
        }
    }
    static void known(String value,Set<String> allowed){if(value==null||!allowed.contains(value))throw fail("EDUCATION_INVALID_RELATION");}
}
