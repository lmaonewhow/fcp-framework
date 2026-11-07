package dev.fcp.dual;

import fcp.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static fcp.Schema.*;

/** Single-process durable test repository. This is NOT the RuoYi JDBC integration. */
public final class FileRecordStore implements RecordStore {
    private Map<String,Object> state;
    private final Path file;
    public FileRecordStore(Path file) {
        this.file=file;
        try { state=Files.exists(file)?map(Json.parse(Files.readString(file))):Json.obj("rows",new TreeMap<>(),"requests",new TreeMap<>(),"events",new ArrayList<>()); }
        catch(IOException e){throw new FcpException("STORAGE","Cannot read test store",e);}
    }
    private String key(String... parts){return Json.write(Arrays.asList(parts));}
    public synchronized Map<String,Object> get(String scope,String kind,String id){
        Object row=obj(state,"rows").get(key(scope,kind,id));
        if(row==null)throw new FcpException("NOT_FOUND","Record is outside the current scope or missing");
        return map(cloneJson(row));
    }
    public synchronized List<Object> list(String scope,String kind){
        List<Object> rows=new ArrayList<>();
        for(Object value:obj(state,"rows").values()){
            Map<String,Object> row=map(value);
            if(scope.equals(row.get("scope"))&&kind.equals(row.get("kind")))rows.add(cloneJson(row));
        }
        return rows;
    }
    @Override public synchronized List<Object> page(String scope,String kind,Set<String> classes,boolean allClasses,String afterId,int limit){
        if(limit<1||limit>1001||classes.size()>32)throw new IllegalArgumentException("Invalid page bounds");
        Comparator<Map<String,Object>> byId=Comparator.comparing(v->str(v.get("id")));
        PriorityQueue<Map<String,Object>> bounded=new PriorityQueue<>(byId.reversed());
        for(Object value:obj(state,"rows").values()){
            Map<String,Object> row=map(value);
            if(!scope.equals(row.get("scope"))||!kind.equals(row.get("kind"))||str(row.get("id")).compareTo(afterId)<=0)continue;
            if(!allClasses&&!classes.contains(obj(row,"body").get("classId")))continue;
            bounded.add(row);if(bounded.size()>limit)bounded.poll();
        }
        List<Map<String,Object>> sorted=new ArrayList<>(bounded);sorted.sort(byId);
        List<Object> result=new ArrayList<>();for(var row:sorted)result.add(cloneJson(row));return result;
    }
    public synchronized List<Object> history(String scope,String kind,String id){
        get(scope,kind,id);List<Object> result=new ArrayList<>();
        for(Object v:arr(state,"events")){Map<String,Object> e=map(v);if(scope.equals(e.get("scope"))&&kind.equals(e.get("kind"))&&id.equals(e.get("id")))result.add(cloneJson(e));}
        return result;
    }
    public synchronized Map<String,Object> replay(String scope,String subject,String requestKey,String fingerprint){
        Object stored=obj(state,"requests").get(key(scope,subject,requestKey));
        if(stored==null)return null;
        Map<String,Object> request=map(stored);
        if(!fingerprint.equals(request.get("fingerprint")))throw new FcpException("IDEMPOTENCY_CONFLICT","Request key has another payload");
        return map(cloneJson(request.get("result")));
    }
    public synchronized Map<String,Object> commit(String scope,String kind,String subject,String requestKey,String fingerprint,String id,long expectedVersion,Map<String,Object> body,Map<String,Object> audit){
        Map<String,Object> previous=replay(scope,subject,requestKey,fingerprint);if(previous!=null)return previous;
        String key=key(scope,kind,id);Map<String,Object> old=map(obj(state,"rows").getOrDefault(key,Map.of()));
        if(number(old.getOrDefault("version",0))!=expectedVersion)throw new FcpException("RECORD_CONFLICT","Record changed; refresh before retrying");
        if(old.isEmpty()&&expectedVersion!=0)throw new FcpException("NOT_FOUND","Record missing");
        Map<String,Object> result=Json.obj("id",id,"scope",scope,"kind",kind,"version",expectedVersion+1,"body",cloneJson(body));
        Map<String,Object> next=Json.obj("rows",new TreeMap<>(obj(state,"rows")),"requests",new TreeMap<>(obj(state,"requests")),"events",new ArrayList<>(arr(state,"events")));
        obj(next,"rows").put(key,result);
        obj(next,"requests").put(key(scope,subject,requestKey),Json.obj("fingerprint",fingerprint,"result",result));
        Map<String,Object> event=new LinkedHashMap<>(map(cloneJson(audit)));
            var trace=fcp.observability.TraceContext.current();if(trace!=null){event.put("traceId",trace.traceId());event.put("requestId",trace.requestId());}event.putAll(Json.obj("scope",scope,"kind",kind,"id",id,"subject",subject,"requestKey",requestKey,"version",expectedVersion+1));
        arr(next,"events").add(event);
        Path temporary=null;
        try{
            Files.createDirectories(file.toAbsolutePath().getParent());
            temporary=Files.createTempFile(file.toAbsolutePath().getParent(),"fcp-commit-",".tmp");
            Files.writeString(temporary,Json.write(next));
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            state=next;return map(cloneJson(result));
        }catch(IOException e){throw new FcpException("STORAGE","Commit failed; previous state retained",e);}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException cleanupError){System.getLogger(FileRecordStore.class.getName()).log(System.Logger.Level.WARNING,"Temporary file cleanup failed",cleanupError);}}
    }
}
