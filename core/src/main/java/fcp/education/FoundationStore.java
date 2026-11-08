package fcp.education;

import fcp.FcpException;
import fcp.Json;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** JDBC-only P042 foundation commands. Caller identity must come from the native adapter.
 * No arbitrary CRUD, caller-supplied permissions, legacy scopes, or default authority rows.
 */
public final class FoundationStore {
    private static final String CAP="education.foundation", VERSION="education-foundation-v1";
    private static final String ORG="ed_education_org_unit", CLS="ed_education_class", TERM="ed_education_term";
    private final DataSource source;
    private final Supplier<String> ids;
    private final String manifest;
    private final Semaphore securityBudget=new Semaphore(4);
    private final LongAdder securityFailures=new LongAdder();
    public FoundationStore(DataSource source,Supplier<String> ids,String manifestDigest) {
        this.source=Objects.requireNonNull(source);this.ids=Objects.requireNonNull(ids);
        if(manifestDigest==null||!manifestDigest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Trusted manifest SHA-256 required");
        this.manifest=manifestDigest;
    }
    public long securityEventFailures(){return securityFailures.sum();}
    public void rejectBoundary(String tenant,String actor){security(tenant,actor,null,"EDUCATION_INVALID_INPUT","DENIED");}
    public void rejectIdentity(String tenant,String actor){security(tenant,actor,null,"EDUCATION_FORBIDDEN","DENIED");}
    public void boundaryAuditUnavailable(){securityFailures.increment();}
    private static FcpException fail(String code){return new FcpException(code,"Education foundation request rejected");}
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
        for(String key:List.of("policy:tenant","cap:education.foundation"))required(c,"SELECT epoch FROM ed_core_write_fence WHERE tenant_id=? AND fence_key=? AND enabled=1 FOR SHARE",tenant,key);
        required(c,"SELECT epoch FROM ed_core_access_epoch WHERE tenant_id=? AND subject_key=? AND enabled=1 FOR SHARE",tenant,actor);
        execution(c,tenant,"tenant");
        required(c,"SELECT tenant_id FROM ed_core_tenant_binding WHERE tenant_id=? AND CAST(host_tenant_key AS BINARY)=CAST(? AS BINARY) AND host_kind='ruoyi-vue-plus' AND status='ACTIVE' FOR SHARE",tenant,tenant);
        // Binary comparison explicitly defeats PAD SPACE/case-insensitive host-column equality.
        nativeExact(c,tenant,null,"SELECT tenant_id FROM sys_tenant WHERE CAST(tenant_id AS BINARY)=CAST(? AS BINARY) AND status='0' AND del_flag='0' AND (expire_time IS NULL OR expire_time>UTC_TIMESTAMP()) FOR SHARE",tenant);
        nativeExact(c,tenant,actor,"SELECT tenant_id,CAST(user_id AS CHAR) FROM sys_user WHERE CAST(tenant_id AS BINARY)=CAST(? AS BINARY) AND CAST(CAST(user_id AS CHAR) AS BINARY)=CAST(? AS BINARY) AND status='0' AND del_flag='0' FOR SHARE",tenant,actor);
        List<Grant> grants=new ArrayList<>();
        String sql="SELECT CAST(r.role_id AS CHAR),g.admin_kind,CAST(g.school_id AS CHAR),g.operation_key,r.tenant_id,t.tenant_id "
            +"FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id "
            +"JOIN ed_host_education_role g ON g.native_role_id=r.role_id AND CAST(r.tenant_id AS BINARY)=CAST(g.tenant_id AS BINARY) "
            +"JOIN sys_role_menu rm ON rm.role_id=r.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id "
            +"JOIN sys_tenant t ON CAST(t.tenant_id AS BINARY)=CAST(g.tenant_id AS BINARY) "
            +"LEFT JOIN sys_tenant_package p ON p.package_id=t.package_id "
            +"WHERE g.tenant_id=? AND CAST(CAST(ur.user_id AS CHAR) AS BINARY)=CAST(? AS BINARY) "
            +"AND r.status='0' AND r.del_flag='0' AND g.enabled=1 AND m.status='0' "
            +"AND CAST(m.perms AS BINARY)=CAST(CONCAT('education:foundation:',g.operation_key) AS BINARY) "
            +"AND (CAST(t.tenant_id AS BINARY)=CAST('000000' AS BINARY) OR "
            +"(p.status='0' AND p.del_flag='0' AND FIND_IN_SET(CAST(m.menu_id AS CHAR),p.menu_ids)>0)) "
            +"ORDER BY r.role_id,g.school_id,g.operation_key,m.menu_id LIMIT 257 FOR SHARE";
        try(var q=statement(c,sql,tenant,actor);var r=q.executeQuery()) {
            while(r.next()){if(!tenant.equals(r.getString(5))||!tenant.equals(r.getString(6)))throw fail("EDUCATION_FORBIDDEN");if(grants.size()==256)throw fail("EDUCATION_RESOURCE_LIMIT");grants.add(new Grant(r.getString(1),r.getString(2),r.getString(3),r.getString(4)));}
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
    private static String permission(String kind,String action){return switch(kind){case "organizations"->"org:"+action;case "classes"->"class:"+action;default->throw fail("EDUCATION_INVALID_INPUT");};}
    private void school(Connection c,Auth a,String school,String operation)throws SQLException {
        id(school);if(!a.permits(school,operation))throw fail("EDUCATION_FORBIDDEN");
        execution(c,a.tenant,"school:"+school);
        Map<String,Object> row=org(c,a.tenant,school,false);
        if(!"SCHOOL".equals(row.get("orgKind"))||row.get("parentId")!=null||!effective(row))throw fail("EDUCATION_NOT_FOUND");
    }
    private static boolean effective(Map<String,Object> org) {Instant t=Instant.now();return !Instant.parse((String)org.get("validFrom")).isAfter(t)&&(org.get("validTo")==null||Instant.parse((String)org.get("validTo")).isAfter(t));}
    private static Map<String,Object> org(Connection c,String tenant,String id,boolean write)throws SQLException {
        try(var q=statement(c,"SELECT id,parent_id,org_code,org_kind,name,valid_from,valid_to,created_at,row_version FROM "+ORG+" WHERE tenant_id=? AND id=? "+(write?"FOR UPDATE":"FOR SHARE"),tenant,id);var r=q.executeQuery()) {
            if(!r.next())throw fail("EDUCATION_NOT_FOUND");return orgRow(r);
        }
    }
    private static Map<String,Object> orgRow(ResultSet r)throws SQLException{return map("id",textId(r,1),"parentId",textId(r,2),"orgCode",r.getString(3),"orgKind",r.getString(4),"name",r.getString(5),"validFrom",dateTime(r,6),"validTo",dateTime(r,7),"createdAt",dateTime(r,8),"rowVersion",textId(r,9));}
    private static String owningSchool(Connection c,String tenant,Map<String,Object> row)throws SQLException {return owningSchool(c,tenant,row,32);}
    private static String owningSchool(Connection c,String tenant,Map<String,Object> row,int maximumNodes)throws SQLException {
        Set<String> visited=new HashSet<>();
        for(int depth=0;depth<maximumNodes;depth++) {
            String key=(String)row.get("id");if(!visited.add(key)||!effective(row))throw fail("EDUCATION_INVALID_RELATION");
            String kind=(String)row.get("orgKind");
            if("SCHOOL".equals(kind)) {if(row.get("parentId")!=null)throw fail("EDUCATION_INVALID_RELATION");return key;}
            if(!Set.of("CAMPUS","DEPARTMENT").contains(kind)||row.get("parentId")==null)throw fail("EDUCATION_INVALID_RELATION");
            row=org(c,tenant,(String)row.get("parentId"),false);
        }throw fail("EDUCATION_INVALID_RELATION");
    }
    private static Map<String,Object> klass(Connection c,String tenant,String school,String id,boolean write)throws SQLException {
        try(var q=statement(c,"SELECT id,school_id,term_id,class_code,name,grade_code,created_at,row_version FROM "+CLS+" WHERE tenant_id=? AND school_id=? AND id=? "+(write?"FOR UPDATE":"FOR SHARE"),tenant,school,id);var r=q.executeQuery()) {
            if(!r.next())throw fail("EDUCATION_NOT_FOUND");return classRow(r);
        }
    }
    private static Map<String,Object> classRow(ResultSet r)throws SQLException{return map("id",textId(r,1),"schoolId",textId(r,2),"termId",textId(r,3),"classCode",r.getString(4),"name",r.getString(5),"gradeCode",r.getString(6),"createdAt",dateTime(r,7),"rowVersion",textId(r,8));}
    private static Map<String,Object> termRow(ResultSet r)throws SQLException{return map("id",textId(r,1),"schoolId",textId(r,2),"termCode",r.getString(3),"startsOn",r.getString(4),"endsOn",r.getString(5),"status",r.getString(6),"rowVersion",textId(r,7));}
    private static final class LedgerInsertRace extends SQLException {
        LedgerInsertRace(SQLException cause){super("Concurrent intent ledger insertion",cause.getSQLState(),cause.getErrorCode(),cause);}
    }
    private static final class RetryLedgerRace extends RuntimeException {}
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException;}
    private <T>T transaction(String tenant,String actor,String intent,Work<T> work) {
        boolean committing=false,rolledBack=false;
        try(Connection c=connection()) {
            try {T value=work.run(c);committing=true;c.commit();return value;}
            catch(RuntimeException|SQLException e){if(!committing)try{c.rollback();rolledBack=true;}catch(SQLException ignored){}throw e;}
        } catch(SQLException e) {
            if(e instanceof LedgerInsertRace && !committing && rolledBack)throw new RetryLedgerRace();
            String code=committing?"EDUCATION_COMMIT_UNKNOWN":(e.getErrorCode()==1062?"EDUCATION_CONFLICT":Set.of(1205,1213).contains(e.getErrorCode())?"EDUCATION_RETRY_SAME_INTENT":"EDUCATION_AUTHORITY_UNAVAILABLE");
            security(tenant,actor,intent,code,committing?"UNKNOWN":"FAILED");throw fail(code);
        } catch(RuntimeException e){
            String[] rejection=runtimeFailure(e,committing);
            security(tenant,actor,intent,rejection[0],rejection[1]);throw fail(rejection[0]);
        }
    }
    private static String[] runtimeFailure(RuntimeException error,boolean committing) {
        // The attempt boundary takes precedence over every RuntimeException subtype.
        if(committing)return new String[]{"EDUCATION_COMMIT_UNKNOWN","UNKNOWN"};
        if(error instanceof IllegalArgumentException)return new String[]{"EDUCATION_INVALID_INPUT","DENIED"};
        if(error instanceof FcpException domain)return new String[]{domain.code,"DENIED"};
        return new String[]{"EDUCATION_AUTHORITY_UNAVAILABLE","FAILED"};
    }
    private static String schoolScopeSql(int schoolCount,boolean tenantWide) {
        if(tenantWide)return "";
        if(schoolCount<1||schoolCount>256)throw fail("EDUCATION_FORBIDDEN");
        return " AND id IN ("+String.join(",",Collections.nCopies(schoolCount,"?"))+")";
    }
    private void security(String tenant,String actor,String intent,String code,String outcome) {
        if(!securityBudget.tryAcquire()){securityFailures.increment();return;}
        try(Connection c=connection()) {
            EducationScalars.tenantId(tenant);str(actor,100);String at=now();
            single(c,"INSERT INTO ed_core_security_event(tenant_id,id,actor_key,operation_id,request_attempt_id,event_code,outcome,safe_context_json,occurred_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",tenant,nextId(),actor,intent,UUID.randomUUID().toString(),code,outcome,"{}",timestamp(at),timestamp(at));c.commit();
        }catch(Exception unavailable){securityFailures.increment();}finally{securityBudget.release();}
    }
    private static Map<String,Object> operations(Auth a,String school){boolean read=a.permits(school,"read");return map("orgList",read,"orgDetail",read,"orgCreate",a.permits(school,"org:create"),"orgRename",a.permits(school,"org:rename"),"classList",read,"classDetail",read,"classCreate",a.permits(school,"class:create"),"classRename",a.permits(school,"class:rename"),"termList",read,"personRename",false);}
    public Map<String,Object> capabilities(String tenant,String actor) {
        return transaction(tenant,actor,null,c->{Auth a=authorize(c,tenant,actor);Map<String,Object> ops=map("orgList",false,"orgDetail",false,"orgCreate",false,"orgRename",false,"classList",false,"classDetail",false,"classCreate",false,"classRename",false,"termList",false,"personRename",false,"schoolCreate",false);for(Grant g:a.grants)for(var e:operations(a,g.school).entrySet())ops.merge(e.getKey(),e.getValue(),(x,y)->Boolean.TRUE.equals(x)||Boolean.TRUE.equals(y));ops.put("schoolCreate",a.tenant("school:create"));return map("schemaVersion",VERSION,"status","READY","operations",ops,"readiness",map("schoolAuthorization","IMPLEMENTED","transactionProtocol","IMPLEMENTED","nativeAcceptance","NOT_RUN"),"blockers",List.of(),"securityEventFailures",securityEventFailures());});
    }
    public Map<String,Object> schools(String tenant,String actor) {return schools(tenant,actor,null,100);}
    public Map<String,Object> schools(String tenant,String actor,String afterId,int limit) {
        return transaction(tenant,actor,null,c->{Auth a=authorize(c,tenant,actor);if(a.grants.stream().noneMatch(g->g.operation.equals("read")))throw fail("EDUCATION_FORBIDDEN");String after=EducationScalars.unsignedBigint(afterId==null?"0":afterId,false);if(limit<1||limit>100)throw fail("EDUCATION_INVALID_INPUT");List<Map<String,Object>> items=new ArrayList<>();
            boolean tenantWide=a.tenant("read");
            List<String> allowedSchools=a.grants.stream().filter(g->g.operation.equals("read")&&g.kind.equals("SCHOOL_ADMIN"))
                .map(g->id(g.school)).distinct().sorted().toList();
            String at=now();List<Object> parameters=new ArrayList<>(List.of(tenant,after,timestamp(at),timestamp(at)));
            if(!tenantWide)parameters.addAll(allowedSchools);
            parameters.add(limit+1);
            String sql="SELECT id,parent_id,org_code,org_kind,name,valid_from,valid_to,created_at,row_version FROM "+ORG
                +" WHERE tenant_id=? AND id>? AND org_kind='SCHOOL' AND parent_id IS NULL AND valid_from<=? AND (valid_to IS NULL OR valid_to>?)"
                +schoolScopeSql(allowedSchools.size(),tenantWide)+" ORDER BY id LIMIT ? FOR SHARE";
            try(var q=statement(c,sql,parameters.toArray());var r=q.executeQuery()) {
                while(r.next()){Map<String,Object> row=orgRow(r);String school=(String)row.get("id");
                    if(!a.permits(school,"read"))throw fail("EDUCATION_FORBIDDEN");
                    boolean ready;try(var gate=statement(c,"SELECT epoch FROM ed_core_execution_grant WHERE tenant_id=? AND scope_key=? AND capability_key=? AND manifest_digest=? AND enabled=1 FOR SHARE",tenant,"school:"+school,CAP,manifest);var rs=gate.executeQuery()){ready=rs.next();}
                    Map<String,Object> ops=operations(a,school);if(!ready)ops.replaceAll((key,value)->false);
                    items.add(map("id",school,"name",row.get("name"),"rowVersion",row.get("rowVersion"),"operations",ops,"readiness",ready?"READY":"EXECUTION_GRANT_MISSING"));
                }
            }String next=null;if(items.size()>limit){items.remove(items.size()-1);next=(String)items.get(items.size()-1).get("id");}return map("items",items,"nextAfterId",next);
        });
    }
    public Map<String,Object> list(String tenant,String actor,String schoolId,String kind,String afterId,int limit,String termId) {
        return transaction(tenant,actor,null,c->{Auth a=authorize(c,tenant,actor);school(c,a,schoolId,"read");String after=EducationScalars.unsignedBigint(afterId==null?"0":afterId,false);if(limit<1||limit>100)throw fail("EDUCATION_INVALID_INPUT");List<Map<String,Object>> items=new ArrayList<>();
            if(kind.equals("organizations")) {
                // Traverse only this authorized school's descendants. An unrelated school's
                // malformed hierarchy or row count must never poison this scope's pagination.
                // Level 32 is included as an error sentinel, never silently truncated.
                String tree="WITH RECURSIVE school_tree(id,depth,visited,invalid) AS ("
                    +"SELECT id,0,CAST(CONCAT('/',id,'/') AS CHAR(800)),0 FROM "+ORG+" WHERE tenant_id=? AND id=? "
                    +"UNION ALL SELECT child.id,parent.depth+1,CONCAT(parent.visited,child.id,'/'),"
                    +"IF(parent.depth>=31 OR LOCATE(CONCAT('/',child.id,'/'),parent.visited)>0 OR child.org_kind NOT IN ('CAMPUS','DEPARTMENT'),1,0) "
                    +"FROM "+ORG+" child JOIN school_tree parent ON child.parent_id=parent.id "
                    +"WHERE child.tenant_id=? AND parent.invalid=0 AND child.valid_from<=? "
                    +"AND (child.valid_to IS NULL OR child.valid_to>?)) "
                    +"SELECT id,invalid FROM school_tree WHERE invalid=1 OR id>? ORDER BY invalid DESC,id LIMIT ?";
                List<String> candidates=new ArrayList<>();String at=now();
                try(var q=statement(c,tree,tenant,schoolId,tenant,timestamp(at),timestamp(at),after,limit+1);var r=q.executeQuery()) {
                    while(r.next()){if(r.getInt(2)!=0)throw fail("EDUCATION_INVALID_RELATION");candidates.add(textId(r,1));}
                }
                // CTE materialization itself is not claimed as a locking read. Re-read the
                // bounded output and each actual ancestor under locks before returning data.
                for(String candidate:candidates){Map<String,Object> row=org(c,tenant,candidate,false);if(!schoolId.equals(owningSchool(c,tenant,row)))throw fail("EDUCATION_INVALID_RELATION");items.add(row);}
            } else if(kind.equals("classes")) {
                id(termId);try(var q=statement(c,"SELECT id,school_id,term_id,class_code,name,grade_code,created_at,row_version FROM "+CLS+" WHERE tenant_id=? AND school_id=? AND term_id=? AND id>? ORDER BY id LIMIT ? FOR SHARE",tenant,schoolId,termId,after,limit+1);var r=q.executeQuery()){while(r.next())items.add(classRow(r));}
            } else if(kind.equals("terms")) {
                try(var q=statement(c,"SELECT id,school_id,term_code,starts_on,ends_on,status,row_version FROM "+TERM+" WHERE tenant_id=? AND school_id=? AND id>? ORDER BY id LIMIT ? FOR SHARE",tenant,schoolId,after,limit+1);var r=q.executeQuery()){while(r.next())items.add(termRow(r));}
            } else throw fail("EDUCATION_INVALID_INPUT");
            String next=null;if(items.size()>limit){items.remove(items.size()-1);next=(String)items.get(items.size()-1).get("id");}return map("items",items,"nextAfterId",next);
        });
    }
    public Map<String,Object> detail(String tenant,String actor,String schoolId,String kind,String objectId) {
        return transaction(tenant,actor,null,c->{Auth a=authorize(c,tenant,actor);id(objectId);String resolved=schoolId==null?locateSchool(c,tenant,kind,objectId):schoolId;school(c,a,resolved,"read");if(kind.equals("classes"))return klass(c,tenant,resolved,objectId,false);if(!kind.equals("organizations"))throw fail("EDUCATION_INVALID_INPUT");var row=org(c,tenant,objectId,false);if(!resolved.equals(owningSchool(c,tenant,row)))throw fail("EDUCATION_NOT_FOUND");return row;});
    }
    /** Structural parser shared by native boundary tests. No authorization is implied. */
    public static Map<String,Object> validateCommand(String kind,String action,Map<String,Object> input) {
        if(input==null)throw fail("EDUCATION_INVALID_INPUT");
        Set<String> keys;
        if(action.equals("rename")&&Set.of("organizations","classes").contains(kind)) keys=Set.of("intentId","id","expectedRowVersion","name");
        else if(action.equals("create")) keys=switch(kind){case "schools"->Set.of("intentId","parentId","orgCode","orgKind","name","validFrom");case "organizations"->Set.of("intentId","parentId","orgCode","orgKind","name","validFrom");case "classes"->Set.of("intentId","termId","classCode","name","gradeCode");default->throw fail("EDUCATION_INVALID_INPUT");};
        else throw fail("EDUCATION_INVALID_INPUT");
        if(!input.keySet().equals(keys))throw fail("EDUCATION_INVALID_INPUT");
        TreeMap<String,Object> values=new TreeMap<>(input);
        str(values.get("intentId"),100);if(str(values.get("name"),kind.equals("classes")?128:190).isBlank())throw fail("EDUCATION_INVALID_INPUT");
        if(action.equals("rename")){id(values.get("id"));EducationScalars.nextVersion(values.get("expectedRowVersion"));}
        else if(kind.equals("classes")){id(values.get("termId"));str(values.get("classCode"),64);str(values.get("gradeCode"),40);}
        else {
            str(values.get("orgCode"),64);EducationScalars.utcTimestamp(values.get("validFrom"));values.put("validFrom",Instant.parse((String)values.get("validFrom")).toString());
            if(kind.equals("schools")&&(values.get("parentId")!=null||!"SCHOOL".equals(values.get("orgKind"))))throw fail("EDUCATION_INVALID_INPUT");
            if(kind.equals("organizations")){id(values.get("parentId"));if(!Set.of("CAMPUS","DEPARTMENT").contains(values.get("orgKind")))throw fail("EDUCATION_INVALID_INPUT");}
        }
        return Collections.unmodifiableMap(values);
    }
    private static String digest(Object value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(value).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static String namespace(String kind,String action){if(!Set.of("schools","organizations","classes").contains(kind)||!Set.of("create","rename").contains(action))throw fail("EDUCATION_INVALID_INPUT");return "education.foundation."+kind+"."+action;}
    private static String commandPermission(String kind,String action){return kind.equals("schools")?"school:create":permission(kind,action);}
    /** Nonlocking lookup is only a hint. The row and school chain are re-read under locks before use. */
    private String locateSchool(Connection c,String tenant,String kind,String object)throws SQLException {
        if(kind.equals("classes")){try(var q=statement(c,"SELECT school_id FROM "+CLS+" WHERE tenant_id=? AND id=?",tenant,id(object));var r=q.executeQuery()){if(!r.next())throw fail("EDUCATION_NOT_FOUND");return textId(r,1);}}
        if(!kind.equals("organizations"))throw fail("EDUCATION_INVALID_INPUT");
        Set<String> visited=new HashSet<>();String cursor=id(object);
        for(int i=0;i<32;i++) {
            if(!visited.add(cursor))throw fail("EDUCATION_INVALID_RELATION");
            try(var q=statement(c,"SELECT id,parent_id,org_kind FROM "+ORG+" WHERE tenant_id=? AND id=?",tenant,cursor);var r=q.executeQuery()) {
                if(!r.next())throw fail("EDUCATION_NOT_FOUND");
                String parent=textId(r,2),kindValue=r.getString(3);
                if("SCHOOL".equals(kindValue)){if(parent!=null)throw fail("EDUCATION_INVALID_RELATION");return textId(r,1);}
                if(parent==null||!Set.of("CAMPUS","DEPARTMENT").contains(kindValue))throw fail("EDUCATION_INVALID_RELATION");cursor=parent;
            }
        }throw fail("EDUCATION_INVALID_RELATION");
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> receipt(Connection c,Auth a,String namespace,String intent,String fingerprint,String school)throws SQLException {
        try(var q=statement(c,"SELECT actor_key,fingerprint,status,result_json FROM ed_core_command_request WHERE tenant_id=? AND effect_namespace=? AND intent_id=? FOR UPDATE",a.tenant,namespace,intent);var r=q.executeQuery()) {
            if(!r.next())return null;
            if(!a.actor.equals(r.getString(1)))throw fail("EDUCATION_FORBIDDEN");
            if(fingerprint!=null&&!fingerprint.equals(r.getString(2)))throw fail("EDUCATION_CONFLICT");
            if(!"COMMITTED".equals(r.getString(3))||r.getString(4)==null)throw fail("EDUCATION_COMMIT_UNKNOWN");
            Map<String,Object> saved=(Map<String,Object>)Json.parse(r.getString(4));
            if(!Objects.equals(school,saved.get("schoolId")))throw fail("EDUCATION_FORBIDDEN");
            return (Map<String,Object>)saved.get("receipt");
        }
    }
    public Map<String,Object> command(String tenant,String actor,String requestedSchool,String kind,String action,Map<String,Object> input) {
        // One full new transaction is permitted only after a known-rolled-back ledger
        // INSERT 1062. Reauthorization, actual scope and fingerprint are all rechecked.
        // Domain unique violations, deadlocks and commit UNKNOWN never enter this retry.
        for(int retry=0;retry<2;retry++) {
            try{return commandAttempt(tenant,actor,requestedSchool,kind,action,input,retry==1);}
            catch(RetryLedgerRace race){if(retry==1)throw fail("EDUCATION_RETRY_SAME_INTENT");}
        }throw new IllegalStateException("Unreachable retry bound");
    }
    private Map<String,Object> commandAttempt(String tenant,String actor,String requestedSchool,String kind,String action,Map<String,Object> input,boolean recoveryOnly) {
        String attempt=UUID.randomUUID().toString();
        String safeIntent=input!=null&&input.get("intentId") instanceof String s&&s.length()<=100?s:null;
        return transaction(tenant,actor,safeIntent,c->{
            Map<String,Object> body=validateCommand(kind,action,input);String intent=(String)body.get("intentId");String ns=namespace(kind,action);
            Auth a=authorize(c,tenant,actor);String schoolId=requestedSchool;String operation=commandPermission(kind,action);
            boolean root=kind.equals("schools");
            if(root) {if(!action.equals("create")||requestedSchool!=null||!a.tenant("school:create"))throw fail("EDUCATION_FORBIDDEN");}
            else {
                if(schoolId==null&&action.equals("rename"))schoolId=locateSchool(c,tenant,kind,(String)body.get("id"));
                school(c,a,schoolId,operation);
            }
            // Domain aggregate locks precede the command ledger, including for replay.
            Map<String,Object> lockedTarget=null;
            if(action.equals("rename")) {
                lockedTarget=kind.equals("organizations")?org(c,tenant,(String)body.get("id"),true):klass(c,tenant,schoolId,(String)body.get("id"),true);
                if(kind.equals("organizations")&&!schoolId.equals(owningSchool(c,tenant,lockedTarget)))throw fail("EDUCATION_NOT_FOUND");
            } else if(kind.equals("classes")) required(c,"SELECT id FROM "+TERM+" WHERE tenant_id=? AND school_id=? AND id=? AND status IN ('DRAFT','OPEN') FOR SHARE",tenant,schoolId,body.get("termId"));
            else if(!root) {
                Map<String,Object> parent=org(c,tenant,(String)body.get("parentId"),true);
                if(!schoolId.equals(owningSchool(c,tenant,parent,31)))throw fail("EDUCATION_NOT_FOUND");
                if(Instant.parse((String)body.get("validFrom")).isBefore(Instant.parse((String)parent.get("validFrom")))||parent.get("validTo")!=null)throw fail("EDUCATION_INVALID_RELATION");
            }
            String fingerprint=digest(map("actor",actor,"schoolId",schoolId,"namespace",ns,"body",body));
            // The current permission is checked even for an already committed intent.
            Map<String,Object> previous=receipt(c,a,ns,intent,fingerprint,schoolId);if(previous!=null)return previous;
            // A primary-ID collision is not an intent race. The bounded recovery may
            // only return an existing matching receipt; it must never attempt a new write.
            if(recoveryOnly)throw fail("EDUCATION_CONFLICT");
            String at=now(),requestId=nextId();
            try {
                single(c,"INSERT INTO ed_core_command_request(tenant_id,id,actor_key,effect_namespace,intent_id,fingerprint,status,result_json,expires_at,created_at,row_version,contract_version,normalization_version) VALUES(?,?,?,?,?,?,'ACCEPTED',NULL,NULL,?,0,?,?)",tenant,requestId,actor,ns,intent,fingerprint,timestamp(at),VERSION,"foundation-intent-v1");
            } catch(SQLException insertion) {
                if(insertion.getErrorCode()==1062)throw new LedgerInsertRace(insertion);
                throw insertion;
            }
            Map<String,Object> record;
            if(action.equals("rename")) {
                String object=(String)body.get("id");Map<String,Object> before=lockedTarget;
                String expected=(String)body.get("expectedRowVersion"),next=EducationScalars.nextVersion(expected);
                if(!expected.equals(before.get("rowVersion")))throw fail("EDUCATION_CONFLICT");
                single(c,"UPDATE "+(kind.equals("organizations")?ORG:CLS)+" SET name=?,row_version=? WHERE tenant_id=? AND id=? AND row_version=?",body.get("name"),next,tenant,object,expected);
                record=kind.equals("organizations")?org(c,tenant,object,false):klass(c,tenant,schoolId,object,false);
            } else if(kind.equals("classes")) {
                String term=id(body.get("termId"));
                // This command does not change the academic term. Frozen/archived terms reject creation.
                required(c,"SELECT id FROM "+TERM+" WHERE tenant_id=? AND school_id=? AND id=? AND status IN ('DRAFT','OPEN') FOR SHARE",tenant,schoolId,term);
                String object=nextId();single(c,"INSERT INTO "+CLS+"(tenant_id,id,school_id,term_id,class_code,name,grade_code,created_at,row_version) VALUES(?,?,?,?,?,?,?,?,0)",tenant,object,schoolId,term,body.get("classCode"),body.get("name"),body.get("gradeCode"),timestamp(at));record=klass(c,tenant,schoolId,object,false);
            } else {
                String parent=root?null:(String)body.get("parentId");
                if(!root) {
                    Map<String,Object> parentRow=org(c,tenant,parent,true);
                    if(!schoolId.equals(owningSchool(c,tenant,parentRow,31)))throw fail("EDUCATION_NOT_FOUND");
                    if(Instant.parse((String)body.get("validFrom")).isBefore(Instant.parse((String)parentRow.get("validFrom")))||parentRow.get("validTo")!=null)throw fail("EDUCATION_INVALID_RELATION");
                }
                String object=nextId();single(c,"INSERT INTO "+ORG+"(tenant_id,id,parent_id,org_code,org_kind,name,valid_from,valid_to,created_at,row_version) VALUES(?,?,?,?,?,?,?,NULL,?,0)",tenant,object,parent,body.get("orgCode"),root?"SCHOOL":body.get("orgKind"),body.get("name"),timestamp((String)body.get("validFrom")),timestamp(at));record=org(c,tenant,object,false);
            }
            Map<String,Object> result=map("operation",map("intentId",intent,"outcome","COMMITTED"),"record",record);
            if(root)result.put("activation",map("status","EXECUTION_GRANT_REQUIRED","schoolId",record.get("id")));
            String object=(String)record.get("id"),type=kind.equals("classes")?"class":"org_unit";
            // Audit stores only approved changed field keys and reference/version, not a full record.
            Map<String,Object> changes=map("fields",action.equals("rename")?List.of("name"):kind.equals("classes")?List.of("school_id","term_id","class_code","name","grade_code"):List.of("parent_id","org_code","org_kind","name","valid_from"),"rowVersion",record.get("rowVersion"));
            single(c,"INSERT INTO ed_core_audit_event(tenant_id,id,actor_key,action_key,owner_domain,object_id,trace_id,changes_json,occurred_at,created_at,object_type,operation_id,request_attempt_id,result,manifest_digest,config_revision,rule_revision,audit_schema_version) VALUES(?,?,?,?,?,?,NULL,?,?,?,?,?,?,'COMMITTED',?,?,?,'audit-v2')",tenant,nextId(),actor,ns,"education",object,Json.write(changes),timestamp(at),timestamp(at),type,intent,attempt,manifest,VERSION,VERSION);
            // Aggregate seq=1 at creation and next row_version+1 thereafter; no silent uint64 overflow.
            String sequence=EducationScalars.nextVersion(record.get("rowVersion"));
            single(c,"INSERT INTO ed_core_outbox(tenant_id,id,event_id,aggregate_type,aggregate_id,aggregate_seq,event_type,payload,status,available_at,created_at,row_version) VALUES(?,?,?,?,?,?,?,?,'NEW',?,?,0)",tenant,nextId(),UUID.randomUUID().toString(),type,object,sequence,ns+".v1",Json.write(map("objectId",object,"rowVersion",record.get("rowVersion"))),timestamp(at),timestamp(at));
            single(c,"UPDATE ed_core_command_request SET status='COMMITTED',result_json=?,row_version=1 WHERE tenant_id=? AND id=? AND status='ACCEPTED' AND row_version=0",Json.write(map("schoolId",schoolId,"receipt",result)),tenant,requestId);
            return result;
        });
    }
    public Map<String,Object> operation(String tenant,String actor,String schoolId,String kind,String action,String intentId) {
        return transaction(tenant,actor,null,c->{str(intentId,100);Auth a=authorize(c,tenant,actor);String ns=namespace(kind,action);
            if(kind.equals("schools")){if(schoolId!=null||!a.tenant("school:create"))throw fail("EDUCATION_FORBIDDEN");}
            else school(c,a,schoolId,commandPermission(kind,action));
            var saved=receipt(c,a,ns,intentId,null,schoolId);return saved!=null?saved:map("operation",map("intentId",intentId,"outcome","UNKNOWN"));
        });
    }
}
