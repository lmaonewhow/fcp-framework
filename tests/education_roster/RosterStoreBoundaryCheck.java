import fcp.education.RosterStore;
import fcp.FcpException;
import java.lang.reflect.*;
import java.util.*;

/** Structural tests only. No mock JDBC, synthetic login or substitute native acceptance. */
public final class RosterStoreBoundaryCheck {
    static int checks;
    static void ok(boolean value){checks++;if(!value)throw new AssertionError("roster check "+checks);}
    static void rejected(Runnable work){try{work.run();throw new AssertionError("unexpected accept");}catch(FcpException|IllegalArgumentException expected){checks++;}}
    static Object invoke(Class<?> owner,String name,Class<?>[] types,Object...values) {
        try{Method method=owner.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(null,values);}
        catch(InvocationTargetException error){if(error.getCause() instanceof RuntimeException failure)throw failure;throw new IllegalStateException(error);}
        catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
    }
    static String query(String kind,boolean detail){return (String)invoke(RosterStore.class,"query",new Class<?>[]{String.class,boolean.class},kind,detail);}
    public static void main(String[] args)throws Exception {
        ok(RosterStore.page(Map.of(),true).equals(new RosterStore.Page("0",50)));
        ok(RosterStore.page(Map.of("afterId",List.of("18446744073709551615"),"limit",List.of("100")),true).limit()==100);
        for(String limit:List.of("0","101","1000","01","-1","1.0"," 1","1 ",""))rejected(()->RosterStore.page(Map.of("limit",List.of(limit)),true));
        for(String after:List.of("01","-1","1.0"," 1","18446744073709551616","1 OR 1=1",""))rejected(()->RosterStore.page(Map.of("afterId",List.of(after)),true));
        for(String field:List.of("tenant","tenantId","actor","teacher","person","personId","scope","purpose","purposeKey","epoch","manifest","termId","schoolId","status","privateRef","studentCode","subjectCode"))rejected(()->RosterStore.page(Map.of(field,List.of("1")),true));
        rejected(()->RosterStore.page(Map.of("limit",List.of("1","100")),true));
        rejected(()->RosterStore.page(Map.of("afterId",List.of("1","2")),true));
        rejected(()->RosterStore.page(Map.of("limit",List.of()),true));
        rejected(()->RosterStore.page(Map.of("afterId",List.of("0")),false));
        rejected(()->RosterStore.page(Map.of("limit",List.of("50")),false));
        for(String kind:List.of("persons","people","enrollment","terms","assignments; DELETE","membership",""))rejected(()->query(kind,false));
        for(String kind:List.of("enrollments","memberships","assignments"))for(boolean detail:List.of(false,true)) {
            String sql=query(kind,detail);ok(sql.contains(".tenant_id=?")&&sql.contains("school_id=CAST(? AS UNSIGNED)")&&sql.endsWith("LIMIT ? FOR SHARE"));
            ok(sql.chars().filter(v->v=='?').count()==4);
            ok(sql.split("CAST\\(\\? AS UNSIGNED\\)",-1).length-1==2);
            ok(sql.contains(detail?".id=CAST(? AS UNSIGNED)":".id>CAST(? AS UNSIGNED)"));
            for(String sensitive:List.of("display_name","private_ref","student_code","subject_code","change_reason","external_map","account_link","identity_relation"))ok(!sql.contains(sensitive));
            ok(!sql.contains("SELECT *"));
        }
        ok(query("assignments",false).contains("JOIN ed_education_class c ON c.tenant_id=a.tenant_id AND c.id=a.class_id"));
        ok(!query("assignments",false).contains("a.school_id"));
        ok(invoke(RosterStore.class,"schoolScopeSql",new Class<?>[]{int.class,boolean.class},2,false).equals(" AND id IN (CAST(? AS UNSIGNED),CAST(? AS UNSIGNED))"));
        ok("student:read".equals(invoke(RosterStore.class,"operation",new Class<?>[]{String.class},"memberships")));
        ok("teacher:read".equals(invoke(RosterStore.class,"operation",new Class<?>[]{String.class},"assignments")));
        Class<?>[] grantTypes={String.class,String.class,String.class};
        invoke(RosterStore.class,"validateGrant",grantTypes,"TENANT_ADMIN","0","student:read");checks++;
        invoke(RosterStore.class,"validateGrant",grantTypes,"SCHOOL_ADMIN","18446744073709551615","teacher:read");checks++;
        for(String[] invalid:List.of(new String[]{"ADMIN","0","student:read"},new String[]{"SCHOOL_ADMIN","0","student:read"},new String[]{"TENANT_ADMIN","1","teacher:read"},new String[]{"TENANT_ADMIN","0","read"},new String[]{"TENANT_ADMIN","0","person:display"}))rejected(()->invoke(RosterStore.class,"validateGrant",grantTypes,(Object[])invalid));
        var blocked=RosterStore.blocked();ok(blocked.get("status").equals("BLOCKED"));
        ok(((Map<?,?>)blocked.get("operations")).values().stream().allMatch(Boolean.FALSE::equals));
        ok(((Map<?,?>)blocked.get("readiness")).get("personDisplayPolicy").equals("PENDING"));
        Class<?> resolver=Class.forName("fcp.education.RosterPersonResolver");
        Class<?>[] intervalTypes={String.class,String.class};String start="2026-01-01T00:00:00Z",end="2026-02-01T00:00:00Z";
        invoke(resolver,"validateInterval",intervalTypes,start,null);checks++;
        rejected(()->invoke(resolver,"validateInterval",intervalTypes,start,start));
        rejected(()->invoke(resolver,"validateInterval",intervalTypes,end,start));
        Class<?>[] withinTypes={String.class,String.class,String.class,String.class};
        invoke(resolver,"validateWithin",withinTypes,start,end,start,end);checks++;
        rejected(()->invoke(resolver,"validateWithin",withinTypes,start,null,start,end));
        rejected(()->invoke(resolver,"validateWithin",withinTypes,start,end,end,null));
        Class<?>[] temporalTypes={String.class,String.class,String.class};
        ok(invoke(resolver,"temporalContext",temporalTypes,start,end,start).equals("CURRENT"));
        ok(invoke(resolver,"temporalContext",temporalTypes,start,end,end).equals("HISTORICAL"));
        ok(invoke(resolver,"temporalContext",temporalTypes,end,null,start).equals("FUTURE"));
        for(String status:List.of("UNKNOWN","active","LEFT ",""))rejected(()->invoke(RosterStore.class,"known",new Class<?>[]{String.class,Set.class},status,Set.of("ACTIVE","LEFT","RESTRICTED")));
        System.out.println("PASS roster pure boundaries: "+checks+" assertions; MySQL/native NOT_RUN");
    }
}
