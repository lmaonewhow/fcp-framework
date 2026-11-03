package dev.fcp.school;

import fcp.*;
import dev.fcp.dual.*;
import java.time.Instant;
import java.util.*;
import static fcp.Schema.*;

/** Java owns validation, row authorization, idempotency and commit. Python only receives scores. */
public final class EvaluationModule implements Engine.Provider {
    private final RecordStore store;
    private static final String KIND="evaluation";
    public EvaluationModule(RecordStore store){this.store=Objects.requireNonNull(store);}
    private boolean visible(Engine.Context ctx, Map<String,Object> row){
        return Boolean.TRUE.equals(ctx.attributes.get("allClasses")) ||
            arr(ctx.attributes,"classes").contains(obj(row,"body").get("classId"));
    }
    private void check(Engine.Context ctx,Map<String,Object> row){
        if(!visible(ctx,row))throw new FcpException("FORBIDDEN","Class is outside the assigned teaching scope");
    }
    private List<Object> page(Engine.Context ctx,String cursor,int limit){
        Set<String> classes=new HashSet<>();for(Object value:arr(ctx.attributes,"classes"))classes.add(str(value));
        return store.page(ctx.scope(),KIND,classes,Boolean.TRUE.equals(ctx.attributes.get("allClasses")),cursor,limit);
    }
    @Override public Object invoke(Engine.Context ctx,String export,String operation,Map<String,Object> input){
        String subject=str(ctx.attributes.get("subject"));
        if(operation.equals("list")){
            String cursor=(String)input.getOrDefault("cursor","");
            int limit=(int)number(input.getOrDefault("limit",25));
            List<Object> fetched=page(ctx,cursor,limit+1);
            boolean more=fetched.size()>limit;
            List<Object> items=more?new ArrayList<>(fetched.subList(0,limit)):fetched;
            return Json.obj("items",items,"nextCursor",more?map(items.get(items.size()-1)).get("id"):null);
        }
        if(operation.equals("get")||operation.equals("history")){
            String id=str(input.get("id"));Map<String,Object> row=store.get(ctx.scope(),KIND,id);check(ctx,row);
            return operation.equals("get")?row:Json.obj("items",store.history(ctx.scope(),KIND,id));
        }
        if(operation.equals("analyze")){
            List<Object> rows=page(ctx,"",1001);
            if(rows.size()>1000)throw new FcpException("RESULT_LIMIT","Select a smaller reporting window before analysis");
            List<Object> scores=rows.stream().map(v->obj(map(v),"body").get("score")).toList();
            // No student names, user identity, credentials or database connection leave the host.
            try{return ctx.call("stats","analyze",Json.obj("scores",scores));}
            catch(FcpException error){if(error.code.equals("DEPENDENCY_MISSING"))throw new FcpException("PLUGIN_DISABLED","Statistics plugin is disabled");throw error;}
        }
        if(!operation.equals("create"))throw new FcpException("OPERATION_MISSING",operation);
        Map<String,Object> body=obj(input,"data");
        check(ctx,Json.obj("body",body));
        String requestKey=str(input.get("requestKey"));
        String fingerprint=Workflow.digest(Json.obj("capability",KIND,"operation",operation,"input",input));
        Map<String,Object> replay=store.replay(ctx.scope(),subject,requestKey,fingerprint);
        if(replay!=null){check(ctx,store.get(ctx.scope(),KIND,str(replay.get("id"))));return replay;}
        Map<String,Object> rowBody=new LinkedHashMap<>(body);
        rowBody.put("owner",subject);rowBody.put("createdAt",Instant.now().toString());
        String id=UUID.randomUUID().toString();
        Map<String,Object> audit=Json.obj("operation","create","engine","java-native","timestamp",Instant.now().toString());
        return ctx.atomic(()->store.commit(ctx.scope(),KIND,subject,requestKey,fingerprint,id,0,rowBody,audit));
    }
}
