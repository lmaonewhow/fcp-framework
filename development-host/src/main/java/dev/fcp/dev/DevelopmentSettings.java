package dev.fcp.dev;

import fcp.*;
import fcp.control.*;
import fcp.host.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** Tenant-scoped dev-host configuration only, not plugin data or credential storage. */
final class DevelopmentSettings implements AutoCloseable {
    private final FileStateJournal journal;
    DevelopmentSettings(Path root)throws IOException{journal=new FileStateJournal(root);}
    synchronized Map<String,Object> get(String tenant)throws IOException {
        var snapshot=journal.load();var values=snapshot.json()==null?Map.<String,Object>of():Schema.map(Json.parse(snapshot.json()));
        return Map.of("revision",snapshot.revision(),"values",values.getOrDefault(tenant,Map.of()));
    }
    synchronized Map<String,Object> put(String tenant,long expected,Map<String,Object> values)throws IOException {
        if(values.size()>32||values.keySet().stream().anyMatch(k->!k.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}"))||Json.write(values).length()>16384)throw new FcpException("INVALID_ARGUMENT","Invalid development configuration");
        // Plain JSON preferences only; no executable code, URLs, credentials, or identity interpretation.
        var snapshot=journal.load();if(snapshot.revision()!=expected)throw new FcpException("VERSION_CONFLICT","Configuration changed; reload before saving");
        Map<String,Object> all=snapshot.json()==null?new LinkedHashMap<>():new LinkedHashMap<>(Schema.map(Json.parse(snapshot.json())));
        all.put(tenant,values);journal.compareAndSet(expected,Json.write(all));return get(tenant);
    }
    public void close()throws IOException{journal.close();}
}
