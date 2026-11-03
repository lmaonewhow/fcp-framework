import fcp.*;
import dev.fcp.dual.HostRequest;
import dev.fcp.dual.HostResponse;
import java.util.*;
import static fcp.Schema.*;
public final class RequestBoundaryRegression {
    static final List<Object> tests=new ArrayList<>(); static final String instance="bdf64a24-ab49-4c82-97ee-ccf900000001";
    static String request(Object generation,Object input){return Json.write(Json.obj("generation",generation,"instance",instance,"input",input));}
    static void expect(String code,Runnable r){try{r.run();throw new AssertionError("Expected "+code);}catch(FcpException e){if(!code.equals(e.code))throw new AssertionError(e.code);}}
    static void test(String name,Runnable r){try{r.run();tests.add(Json.obj("name",name,"status","PASS"));}catch(Throwable e){tests.add(Json.obj("name",name,"status","FAIL","error",e.toString()));}}
    public static void main(String[] args){
        test("valid_envelope",()->HostRequest.parse(request(1,Map.of()),false));
        test("malformed_json_maps_to_input_error",()->expect("INVALID_INPUT",()->HostRequest.parse("{",false)));
        test("duplicate_json_keys_rejected",()->expect("INVALID_INPUT",()->HostRequest.parse("{\"input\":{},\"input\":{},\"generation\":1,\"instance\":\""+instance+"\"}",false)));
        test("client_identity_fields_rejected",()->expect("INVALID_INPUT",()->HostRequest.parse(request(1,Map.of()).replace("\"input\":{}","\"input\":{},\"subject\":\"admin\""),false)));
        test("generation_string_rejected",()->expect("INVALID_INPUT",()->HostRequest.parse(request("1",Map.of()),false)));
        test("generation_fraction_rejected",()->expect("INVALID_INPUT",()->HostRequest.parse(request(1.5,Map.of()),false)));
        test("generation_zero_rejected",()->expect("INVALID_INPUT",()->HostRequest.parse(request(0,Map.of()),false)));
        test("array_input_rejected",()->expect("INVALID_INPUT",()->HostRequest.parse(request(1,List.of()),false)));
        test("utf8_byte_limit_enforced",()->expect("INVALID_INPUT",()->HostRequest.parse(request(1,Json.obj("text","中".repeat(90000))),false)));
        test("unknown_composition_rejected",()->expect("COMPOSITION_MISSING",()->HostRequest.parse(Json.write(Json.obj("generation",1,"instance",instance,"composition","arbitrary.module")),true)));
        test("wire_projection_preserves_numeric_generation",()->{
            Map<String,Object> data=map(Json.parse("{\"generation\":1,\"version\":2}"));
            Map<String,Object> envelope=map(Json.parse(HostResponse.ok(data)));
            if(!(obj(envelope,"data").get("generation") instanceof Number)||number(envelope.get("code"))!=200)throw new AssertionError("Numeric types changed");
        });
        test("wire_projection_preserves_schema_keyword_types",()->{
            Map<String,Object> data=map(Json.parse("{\"contract\":{\"minimum\":0,\"maximum\":1000000000},\"amount\":\"9999999999.99\"}"));
            Map<String,Object> decoded=obj(map(Json.parse(HostResponse.ok(data))),"data");
            if(!(obj(decoded,"contract").get("maximum") instanceof Number)||!decoded.get("amount").equals("9999999999.99"))throw new AssertionError("Schema or amount corrupted");
        });
        long failed=tests.stream().filter(x->map(x).get("status").equals("FAIL")).count();System.out.println(Json.write(Json.obj("suite","host-envelope-boundary","scope","actual shared parser, not Spring HTTP execution","passed",tests.size()-failed,"failed",failed,"tests",tests)));if(failed>0)System.exit(1);
    }
}
