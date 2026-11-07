package fcp.release.version;

import fcp.FcpException;
import fcp.Json;
import fcp.control.StateJournal;
import java.io.IOException;
import java.util.*;

/** Provider over the host's durable CAS journal (JDBC in production). Caller owns journal lifetime. */
public final class JournalVersionProvider implements VersionProvider {
    private final String id;private final StateJournal journal;
    public JournalVersionProvider(String id,StateJournal journal){VersionCodec.id(id);this.id=id;this.journal=Objects.requireNonNull(journal);}
    public String id(){return id;}
    public Set<Capability> capabilities(){return Set.of(Capability.READ,Capability.REGISTER_IMMUTABLE);}
    public synchronized Optional<SystemArtifact> artifact(String id){VersionCodec.id(id);return Optional.ofNullable(load().artifacts.get(id));}
    public synchronized Optional<SystemRelease> release(String id){VersionCodec.id(id);return Optional.ofNullable(load().releases.get(id));}
    public synchronized List<SystemArtifact> artifacts(){return List.copyOf(load().artifacts.values());}
    public synchronized List<SystemRelease> releases(){return List.copyOf(load().releases.values());}
    public synchronized SystemArtifact registerArtifact(SystemArtifact value){
        State state=load();SystemArtifact old=state.artifacts.get(value.id());
        if(old!=null){if(!old.equals(value))throw conflict();return old;}
        for(SystemArtifact a:state.artifacts.values())if(a.kind()==value.kind()&&a.component().equals(value.component())&&a.version().equals(value.version()))throw conflict();
        state.artifacts.put(value.id(),value);save(state);return value;
    }
    public synchronized SystemRelease registerRelease(SystemRelease value){
        State state=load();SystemRelease old=state.releases.get(value.id());
        if(old!=null){if(!old.equals(value))throw conflict();return old;}
        for(SystemRelease r:state.releases.values())if(r.version().equals(value.version()))throw conflict();
        for(String artifact:value.components().values())if(!state.artifacts.containsKey(artifact))throw new FcpException("ARTIFACT_NOT_FOUND","Release references an unknown artifact");
        state.releases.put(value.id(),value);save(state);return value;
    }
    private State load(){
        try {
            StateJournal.Snapshot snapshot=journal.load();State state=new State(snapshot.revision());
            if(snapshot.json()==null){if(snapshot.revision()!=0)throw new IllegalArgumentException("Empty version journal has nonzero revision");return state;}
            Map<String,Object> envelope=VersionCodec.object(Json.parse(snapshot.json()),Set.of("format","sha256","value"));
            if(!new java.math.BigDecimal("1").equals(envelope.get("format"))||!VersionCodec.sha256(VersionCodec.canonical(envelope.get("value"))).equals(envelope.get("sha256")))throw new IllegalArgumentException("Version journal integrity");
            Map<String,Object> value=VersionCodec.object(envelope.get("value"),Set.of("artifacts","releases"));
            if(!(value.get("artifacts") instanceof List<?> artifacts)||!(value.get("releases") instanceof List<?> releases)||artifacts.size()>10000||releases.size()>10000)throw new IllegalArgumentException("Version journal entries");
            Set<String> labels=new HashSet<>();
            for(Object raw:artifacts){SystemArtifact a=VersionCodec.artifact(raw);if(state.artifacts.putIfAbsent(a.id(),a)!=null||!labels.add(a.kind()+"/"+a.component()+"/"+a.version()))throw new IllegalArgumentException("Duplicate artifact identity");}
            labels.clear();
            for(Object raw:releases){SystemRelease r=VersionCodec.release(raw);if(state.releases.putIfAbsent(r.id(),r)!=null||!labels.add(r.version())||!state.artifacts.keySet().containsAll(r.components().values()))throw new IllegalArgumentException("Invalid release references");}
            return state;
        }catch(IOException e){throw new FcpException("VERSION_STORAGE","Version journal unavailable",e);}catch(IllegalArgumentException e){throw new FcpException("VERSION_CORRUPT","Version journal invalid",e);}
    }
    private void save(State state){
        if(state.artifacts.size()>10000||state.releases.size()>10000)throw new FcpException("VERSION_LIMIT","Version catalog entry limit");
        Object value=Map.of("artifacts",state.artifacts.values().stream().map(VersionCodec::artifact).toList(),"releases",state.releases.values().stream().map(VersionCodec::release).toList());
        String json=VersionCodec.canonical(Map.of("format",1,"sha256",VersionCodec.sha256(VersionCodec.canonical(value)),"value",value));
        try{journal.compareAndSet(state.revision,json);}catch(IOException e){throw new FcpException("VERSION_WRITE_UNCERTAIN","Version catalog write failed; reload and reconcile before retry",e);}
    }
    private static FcpException conflict(){return new FcpException("VERSION_CONFLICT","Immutable version already has different content");}
    private static final class State {final long revision;final Map<String,SystemArtifact> artifacts=new TreeMap<>();final Map<String,SystemRelease> releases=new TreeMap<>();State(long revision){this.revision=revision;}}
}
