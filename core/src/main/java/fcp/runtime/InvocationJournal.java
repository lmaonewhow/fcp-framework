package fcp.runtime;

import fcp.*;
import fcp.control.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Durable write-ahead outcomes and bounded runner restart history. UNKNOWN is never replayed. */
final class InvocationJournal implements AutoCloseable {
    private final StateJournal storage;
    private final Map<String,Object> records;
    private long revision;
    private boolean poisoned;
    InvocationJournal(Path directory)throws IOException{this(new FileStateJournal(directory));}
    InvocationJournal(StateJournal storage)throws IOException {
        this.storage=storage;try{var snapshot=storage.load();revision=snapshot.revision();records=snapshot.json()==null?new LinkedHashMap<>():Schema.map(Json.parse(snapshot.json()));}catch(IOException|RuntimeException failure){storage.close();throw failure;}
    }
    synchronized Map<String,Object> begin(String key,String fingerprint){
        live();if(records.containsKey(key)){Map<String,Object> r=Schema.map(records.get(key));if(!fingerprint.equals(r.get("fingerprint")))throw new FcpException("IDEMPOTENCY_CONFLICT","Request content changed");return Schema.map(Json.copy(r));}
        records.put(key,new LinkedHashMap<>(Map.of("fingerprint",fingerprint,"outcome","UNKNOWN")));persist();return null;
    }
    synchronized void complete(String key,Object result){live();Map<String,Object> r=Schema.map(records.get(key));r.put("outcome","COMPLETE");r.put("result",Json.copy(result));persist();}
    synchronized void reserveRestart(String plugin,Instant now,int budget){
        live();String key="runner:"+plugin;List<String> attempts=new ArrayList<>();Object raw=records.get(key);if(raw!=null)for(Object t:Schema.list(raw)){Instant time=Instant.parse(Schema.str(t));if(!time.isBefore(now.minusSeconds(60)))attempts.add(time.toString());}
        if(attempts.size()>=budget)throw new FcpException("RESTART_BUDGET","Runner restart budget exhausted");
        if(!attempts.isEmpty()&&now.isBefore(Instant.parse(attempts.get(attempts.size()-1)).plusMillis(250)))throw new FcpException("RESTART_BACKOFF","Runner restart backoff active",Math.max(1,(java.time.Duration.between(now,Instant.parse(attempts.get(attempts.size()-1)).plusMillis(250)).toNanos()+999999)/1000000));
        attempts.add(now.toString());records.put(key,attempts);persist();
    }
    private void live(){if(poisoned)throw new FcpException("JOURNAL_UNAVAILABLE","Invocation journal failed");}
    private void persist(){try{revision=storage.compareAndSet(revision,Json.write(records)).revision();}catch(IOException e){poisoned=true;throw new FcpException("JOURNAL_UNAVAILABLE","Outcome persistence failed",e);}}
    public void close()throws IOException{storage.close();}
}
