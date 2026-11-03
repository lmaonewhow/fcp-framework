package dev.fcp.dual;

import fcp.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static fcp.Schema.*;

/** Strict public envelope shared by the Spring adapter and dependency-free boundary tests. */
public final class HostRequest {
    private HostRequest(){}
    public static Map<String,Object> parse(String body, boolean publication) {
        if(body == null || body.getBytes(StandardCharsets.UTF_8).length > 262144) throw new FcpException("INVALID_INPUT","Request exceeds 256 KiB UTF-8 limit");
        final Map<String,Object> value;
        try { value=map(Json.parse(body)); }
        catch(IllegalArgumentException error) { throw new FcpException("INVALID_INPUT","Malformed JSON request",error); }
        Set<String> keys=publication?Set.of("composition","generation","instance"):Set.of("input","generation","instance");
        Set<String> allowed=new HashSet<>(keys);allowed.add("expectedCaller");
        if(!value.keySet().containsAll(keys)||!allowed.containsAll(value.keySet()))throw new FcpException("INVALID_INPUT","Unexpected or missing request fields");
        if(value.containsKey("expectedCaller")){
            Map<String,Object> expected=map(value.get("expectedCaller"));
            if(!expected.keySet().equals(Set.of("scope","subject")))throw new FcpException("INVALID_INPUT","Invalid caller precondition");
            for(Object field:expected.values())if(!(field instanceof String text)||text.isBlank()||text.length()>100)throw new FcpException("INVALID_INPUT","Invalid caller precondition");
        }
        Object generation=value.get("generation");
        if(!(generation instanceof Number) || number(generation)<1) throw new FcpException("INVALID_INPUT","generation must be a positive JSON integer");
        String instance=str(value.get("instance"));
        if(!instance.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) throw new FcpException("INVALID_INPUT","Invalid host instance identifier");
        if(publication) {
            if(!Set.of("ruoyi.dual","ruoyi.java-only").contains(value.get("composition"))) throw new FcpException("COMPOSITION_MISSING","Only preinstalled compositions may be selected");
        } else map(value.get("input"));
        return value;
    }
    /** Client values are equality preconditions only; the host still authenticates both fields. */
    public static void checkCaller(Map<String,Object> request,String scope,String subject){
        if(request.containsKey("expectedCaller")){
            Map<String,Object> expected=map(request.get("expectedCaller"));
            if(!Objects.equals(scope,expected.get("scope"))||!Objects.equals(subject,expected.get("subject")))
                throw new FcpException("IDENTITY_CHANGED","Active account changed; refresh without reusing another account's pending request");
        }
    }
}
