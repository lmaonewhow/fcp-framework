package dev.fcp.dual;

import fcp.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static fcp.Schema.*;

/** Shared orchestration: language-neutral rules, host-owned identity and final commit. */
public final class Workflow implements Engine.Provider {
    private final RecordStore store;
    private final String kind;
    public Workflow(String kind,RecordStore store){this.kind=kind;this.store=store;}
    private static Object canonical(Object value){
        if(value instanceof Map<?,?>){Map<String,Object> result=new TreeMap<>();map(value).forEach((k,v)->result.put(k,canonical(v)));return result;}
        if(value instanceof List<?> l)return l.stream().map(Workflow::canonical).toList();return value;
    }
    public static String digest(Object value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(canonical(value)).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private void visible(Engine.Context context,Map<String,Object> row){
        // The host supplies viewAll; it cannot be accepted from HTTP payloads.
        Map<String,Object> body=obj(row,"body");String subject=str(context.attributes.get("subject"));
        if(!Boolean.TRUE.equals(context.attributes.get("viewAll"))&&!subject.equals(body.get("owner"))&&!subject.equals(body.get("assignee")))throw new FcpException("FORBIDDEN","Record access denied");
    }
    public Object invoke(Engine.Context context,String export,String operation,Map<String,Object> input){
        String scope=context.scope(),subject=str(context.attributes.get("subject"));
        if(operation.equals("list")){
            List<Object> rows=new ArrayList<>();
            for(Object row:store.list(scope,kind)){try{visible(context,map(row));rows.add(row);}catch(FcpException e){if(!e.code.equals("FORBIDDEN"))throw e;}}
            return Json.obj("items",rows);
        }
        if(operation.equals("get")||operation.equals("history")){
            Map<String,Object> row=store.get(scope,kind,str(input.get("id")));visible(context,row);
            return operation.equals("get")?row:Json.obj("items",store.history(scope,kind,str(input.get("id"))));
        }
        String requestKey=str(input.get("requestKey"));
        String fingerprint=digest(Json.obj("kind",kind,"operation",operation,"input",input));
        // Authorization has already been checked by Engine, including for replays.
        Map<String,Object> replay=store.replay(scope,subject,requestKey,fingerprint);
        if(replay!=null) {
            // Replays still need current row-level access; a prior grant is not a permanent read token.
            visible(context,store.get(scope,kind,str(replay.get("id"))));
            return replay;
        }
        boolean create=operation.equals("create");
        String id=create?UUID.randomUUID().toString():str(input.get("id"));
        Map<String,Object> row=create?Json.obj("id",id,"version",0,"body",Map.of()):store.get(scope,kind,id);
        if(!create)visible(context,row);
        long expected=number(input.getOrDefault("expectedVersion",0));
        if(expected>=1_000_000_000L)throw new FcpException("VERSION_EXHAUSTED","Record version limit reached; migrate the contract before another write");
        if(expected!=number(row.get("version")))throw new FcpException("RECORD_CONFLICT","Refresh before acting on the latest version");
        Map<String,Object> proposal=map(context.call("rules","plan",Json.obj("action",operation,"actor",subject,"record",obj(row,"body"),"data",obj(input,"data"))));
        Map<String,Object> body=obj(proposal,"body");
        // Tenant/id/version/audit always come from the main container, never the plugin.
        if(!create&&!Objects.equals(body.get("owner"),obj(row,"body").get("owner")))throw new FcpException("INVALID_OUTPUT","Owner is immutable");
        if(create&&!subject.equals(body.get("owner")))throw new FcpException("INVALID_OUTPUT","Invalid owner in proposal");
        Map<String,Object> audit=Json.obj("operation",operation,"from",obj(row,"body").getOrDefault("status",""),"to",body.get("status"),"engine",proposal.get("engine"),"timestamp",Instant.now().toString());
        return context.atomic(()->store.commit(scope,kind,subject,requestKey,fingerprint,id,expected,body,audit));
    }
}
