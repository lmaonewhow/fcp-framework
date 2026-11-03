import fcp.*;
import dev.fcp.dual.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static fcp.Schema.*;

public final class OptimizationRegression {
    static final List<Object> results=new ArrayList<>();
    interface Test{void run()throws Exception;}
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void reject(Runnable task){try{task.run();throw new AssertionError("Invalid JSON accepted");}catch(IllegalArgumentException expected){}}
    static void run(String name,Test test){try{test.run();results.add(Json.obj("name",name,"status","PASS"));}catch(Throwable e){results.add(Json.obj("name",name,"status","FAIL","error",e.toString()));}}
    static Object random(Random r,int depth){
        int kind=r.nextInt(depth>3?4:6);
        return switch(kind){case 0->null;case 1->r.nextBoolean();case 2->new BigDecimal(r.nextInt(100000)).movePointLeft(r.nextInt(5));
        case 3->"中文\\\"\n 😀 "+r.nextInt(100);case 4->{List<Object> a=new ArrayList<>();for(int i=0,n=r.nextInt(5);i<n;i++)a.add(random(r,depth+1));yield a;}
        default->{Map<String,Object> m=new LinkedHashMap<>();for(int i=0,n=r.nextInt(5);i<n;i++)m.put("k"+i,random(r,depth+1));yield m;}};
    }
    public static void main(String[] args)throws Exception{
        run("structural_copy_matches_roundtrip_2000_seeded_values",()->{Random r=new Random(4204);for(int i=0;i<2000;i++){Object a=random(r,0);check(Objects.equals(Json.parse(Json.write(a)),Json.copy(a)),"semantic mismatch");}});
        run("copy_does_not_share_mutable_collections",()->{Map<String,Object> a=Json.obj("n",new ArrayList<>(List.of(Json.obj("v",1))));Map<String,Object> b=map(Json.copy(a));map(list(b.get("n")).get(0)).put("v",2);check(number(map(list(a.get("n")).get(0)).get("v"))==1,"mutated original");});
        run("copy_rejects_cycles",()->{List<Object>a=new ArrayList<>();a.add(a);reject(()->Json.copy(a));});
        run("copy_rejects_non_string_map_keys",()->reject(()->Json.copy(Map.of(3,"x"))));
        run("copy_rejects_unpaired_unicode",()->{reject(()->Json.copy("\uD800"));reject(()->Json.copy(Map.of("\uDC00",1)));});
        run("copy_rejects_unsafe_numbers",()->{reject(()->Json.copy(9007199254740992L));reject(()->Json.copy(Double.NaN));reject(()->Json.copy(Double.POSITIVE_INFINITY));});
        run("copy_preserves_numeric_canonicalization",()->{for(Number n:List.of(1,1L,1.0,new BigDecimal("1.000"),new BigDecimal("0.00001")))check(Json.copy(n).equals(Json.parse(Json.write(n))),"numeric type");});
        run("copy_rejects_excessive_depth",()->{Object a="x";for(int i=0;i<34;i++)a=List.of(a);Object last=a;reject(()->Json.copy(last));});
        run("copy_rejects_oversize_text_and_escaped_text",()->{String a="a".repeat(16*1024*1024);reject(()->Json.copy(a));String b="\u0001".repeat(3*1024*1024);reject(()->Json.copy(b));});
        run("copy_rejects_long_canonical_numeric_token",()->reject(()->Json.copy(new BigDecimal("1E-200"))));
        run("record_copy_on_write_keeps_caller_and_audit_isolated",()->{
            Path p=Files.createTempDirectory("fcp-cow").resolve("rows.json");FileRecordStore store=new FileRecordStore(p);
            Map<String,Object>b=Json.obj("classId","a","score",1),audit=Json.obj("nested",Json.obj("n",1));
            Map<String,Object> row=store.commit("s","evaluation","u","k","fp","1",0,b,audit);
            b.put("score",99);obj(audit,"nested").put("n",99);obj(row,"body").put("score",99);
            check(number(obj(store.get("s","evaluation","1"),"body").get("score"))==1,"caller alias");
            check(number(obj(map(store.history("s","evaluation","1").get(0)),"nested").get("n"))==1,"audit alias");
            check(number(obj(new FileRecordStore(p).get("s","evaluation","1"),"body").get("score"))==1,"restart");
        });
        run("bounded_page_filters_scope_class_cursor_and_orders",()->{
            FileRecordStore s=new FileRecordStore(Files.createTempDirectory("fcp-page").resolve("rows.json"));
            for(int i=0;i<20;i++)s.commit(i%5==0?"other":"s","evaluation","u","k"+i,"fp"+i,String.format("%03d",i),0,Json.obj("classId",i%2==0?"a":"b"),Map.of());
            List<Object> rows=s.page("s","evaluation",Set.of("a"),false,"002",3);
            check(rows.size()==3,"limit");check(rows.stream().map(v->str(map(v).get("id"))).toList().equals(List.of("004","006","008")),"wrong rows");
            check(s.page("s","evaluation",Set.of(),false,"",5).isEmpty(),"empty class grant must deny");
            obj(map(rows.get(0)),"body").put("classId","other");check(obj(s.get("s","evaluation","004"),"body").get("classId").equals("a"),"read alias");
        });
        long failed=results.stream().filter(v->map(v).get("status").equals("FAIL")).count();
        System.out.println(Json.write(Json.obj("suite","optimization","passed",results.size()-failed,"failed",failed,"tests",results)));if(failed>0)System.exit(1);
    }
}
