import fcp.education.FoundationStore;
import fcp.FcpException;
import java.util.*;

/** Pure validation only. No fake JDBC/token/schema and no native claims. */
public final class FoundationStoreBoundaryCheck {
    static int checks;
    static void ok(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    static void rejected(Runnable work){try{work.run();throw new AssertionError("unexpected accept");}catch(FcpException|IllegalArgumentException expected){checks++;}}
    static Map<String,Object> rename(){return new HashMap<>(Map.of("intentId","same-intent","id","18446744073709551615","expectedRowVersion","0","name","校区名称"));}
    public static void main(String[] args) throws Exception {
        ok(FoundationStore.validateCommand("organizations","rename",rename()).get("name").equals("校区名称"));
        ok(FoundationStore.validateCommand("classes","rename",rename()).get("id").equals("18446744073709551615"));
        for(Object bad:Arrays.asList(null,1,1.0,true,"0","01"," 1","18446744073709551616")){
            var b=rename();b.put("id",bad);rejected(()->FoundationStore.validateCommand("organizations","rename",b));
        }
        for(String field:List.of("tenant","actor","schoolId","scope","manifest","status","privateRef","createdAt","row_version")){
            var b=rename();b.put(field,"x");rejected(()->FoundationStore.validateCommand("classes","rename",b));
        }
        for(Object bad:Arrays.asList(null,0,"-1","01","18446744073709551615")){
            var b=rename();b.put("expectedRowVersion",bad);rejected(()->FoundationStore.validateCommand("classes","rename",b));
        }
        var b=rename();b.put("name","x".repeat(129));rejected(()->FoundationStore.validateCommand("classes","rename",b));
        ok(FoundationStore.validateCommand("organizations","rename",b).get("name").equals("x".repeat(129)));
        rejected(()->FoundationStore.validateCommand("organizations","delete",rename()));
        rejected(()->FoundationStore.validateCommand("terms","rename",rename()));
        rejected(()->FoundationStore.validateCommand("people","rename",rename()));
        var root=new HashMap<String,Object>(Map.of("intentId","root","orgCode","001","name","学校","validFrom","2026-11-01T00:00:00Z"));
        root.put("parentId",null);root.put("orgKind","SCHOOL");
        ok(FoundationStore.validateCommand("schools","create",root).get("orgCode").equals("001"));
        root.put("parentId","1");rejected(()->FoundationStore.validateCommand("schools","create",root));
        var org=new HashMap<String,Object>(Map.of("intentId","org","parentId","1","orgCode","001","orgKind","CAMPUS","name","学校","validFrom","2026-11-01T00:00:00Z"));
        ok(FoundationStore.validateCommand("organizations","create",org).get("orgKind").equals("CAMPUS"));
        org.put("validFrom","2026-11-01T00:00:00.000000Z");ok(FoundationStore.validateCommand("organizations","create",org).get("validFrom").equals("2026-11-01T00:00:00Z"));
        org.put("orgKind","SCHOOL");rejected(()->FoundationStore.validateCommand("organizations","create",org));
        org.put("orgKind","DEPARTMENT");org.put("validFrom","2026-11-01T08:00:00+08:00");rejected(()->FoundationStore.validateCommand("organizations","create",org));
        var classify=FoundationStore.class.getDeclaredMethod("runtimeFailure",RuntimeException.class,boolean.class);
        classify.setAccessible(true);
        for(RuntimeException exception:List.of(new IllegalArgumentException("validation"),new FcpException("EDUCATION_CONFLICT","conflict"),new IllegalStateException("driver"))) {
            String[] decision=(String[])classify.invoke(null,exception,true);
            ok(Arrays.equals(decision,new String[]{"EDUCATION_COMMIT_UNKNOWN","UNKNOWN"}));
        }
        ok(Arrays.equals((String[])classify.invoke(null,new IllegalArgumentException(),false),new String[]{"EDUCATION_INVALID_INPUT","DENIED"}));
        ok(Arrays.equals((String[])classify.invoke(null,new FcpException("EDUCATION_CONFLICT","conflict"),false),new String[]{"EDUCATION_CONFLICT","DENIED"}));
        ok(Arrays.equals((String[])classify.invoke(null,new IllegalStateException(),false),new String[]{"EDUCATION_AUTHORITY_UNAVAILABLE","FAILED"}));
        var scope=FoundationStore.class.getDeclaredMethod("schoolScopeSql",int.class,boolean.class);scope.setAccessible(true);
        ok(scope.invoke(null,1,false).equals(" AND id IN (?)"));
        ok(scope.invoke(null,2,false).equals(" AND id IN (?,?)"));
        ok(scope.invoke(null,0,true).equals(""));
        try{scope.invoke(null,0,false);throw new AssertionError("Empty read scope accepted");}
        catch(java.lang.reflect.InvocationTargetException expected){ok(expected.getCause() instanceof FcpException);}
        System.out.println("PASS pure foundation request validation: "+checks+" assertions; MySQL/native NOT_RUN");
    }
}
