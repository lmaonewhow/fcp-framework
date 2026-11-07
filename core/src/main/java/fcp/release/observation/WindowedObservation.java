package fcp.release.observation;

import fcp.release.control.MetricsGate;
import fcp.*;
import fcp.control.StateJournal;
import java.io.IOException;
import java.time.*;
import java.util.*;

/** Single-process bounded exact windows, not a cross-node monitor. Restart creates a fresh source epoch.
 * Incomplete windows never pass; retained request tickets cannot contaminate a new generation/window.
 */
public final class WindowedObservation implements ObservationProvider,RequestObserver {
    private record Binding(String release,long routeRevision) {}
    private record Key(String release,long generation) {}
    private static final class Bucket {
        final Instant opened;long requests,completed,errors,businessErrors,unknown;boolean incomplete,security;
        final ArrayList<Long> durations=new ArrayList<>();
        Bucket(Instant opened){this.opened=opened;}
    }
    private final StateJournal incidents;
    private final Clock clock;private final int limit;private final String epoch=UUID.randomUUID().toString();
    private final Map<Binding,Key> bindings=new LinkedHashMap<>();private final Map<Key,Bucket> buckets=new LinkedHashMap<>();
    private final Set<Key> security=new HashSet<>();private long sequence;private boolean incidentOverflow;
    public WindowedObservation(Clock clock,int maximumSamples){this(clock,maximumSamples,null);}
    public WindowedObservation(Clock clock,int maximumSamples,StateJournal incidents){this.incidents=incidents;this.clock=Objects.requireNonNull(clock);if(maximumSamples<1||maximumSamples>10000)throw new IllegalArgumentException("Window sample bound");limit=maximumSamples;refreshIncidents();}
    /** Call before publishing the corresponding CAS router revision. Only the deployment adapter owns this binding. */
    public synchronized void bind(String release,long generation,long routeRevision){
        if(release==null||!release.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")||generation<1||routeRevision<1)throw new IllegalArgumentException("Invalid observation binding");
        Binding b=new Binding(release,routeRevision);Key k=new Key(release,generation);Key old=bindings.get(b);
        if(old!=null&&!old.equals(k))throw new IllegalStateException("OBSERVATION_BINDING_CONFLICT");
        if(old==null&&bindings.size()>=256)bindings.remove(bindings.keySet().iterator().next());
        if(!buckets.containsKey(k)&&buckets.size()>=32){Key retired=buckets.keySet().iterator().next();buckets.remove(retired);bindings.entrySet().removeIf(e->e.getValue().equals(retired));}
        bindings.put(b,k);buckets.computeIfAbsent(k,x->new Bucket(clock.instant()));
    }
    @Override public synchronized Ticket begin(String release,long routeRevision){
        Key key=bindings.get(new Binding(release,routeRevision));
        if(key==null)throw new IllegalStateException("OBSERVATION_GENERATION_UNBOUND");
        Bucket b=buckets.computeIfAbsent(key,x->new Bucket(clock.instant()));b.requests++;
        if(b.requests>limit)b.incomplete=true;
        return new Ticket(){boolean done;public void complete(boolean technical,BusinessOutcome business,boolean unknown,long nanos){synchronized(WindowedObservation.this){
            if(done){b.incomplete=true;return;}done=true;b.completed++;if(technical)b.errors++;if(business==BusinessOutcome.FAILURE)b.businessErrors++;
            if(unknown)b.unknown++;if(business==null||business==BusinessOutcome.UNKNOWN||nanos<0)b.incomplete=true;
            if(b.durations.size()<limit&&nanos>=0)b.durations.add(nanos);else b.incomplete=true;
        }}};
    }
    /** Trusted security integration only. It is latched for that generation, not cleared by polling. */
    public synchronized void securityViolation(String release,long generation){Key k=new Key(release,generation);if(!buckets.containsKey(k))throw new IllegalStateException("Unknown observation generation");if(!security.contains(k)&&security.size()>=1000){buckets.get(k).security=true;incidentOverflow=true;throw new IllegalStateException("SECURITY_INCIDENT_LIMIT");}security.add(k);
        if(incidents!=null)try{var snapshot=incidents.load();mergeIncidents(snapshot.json());if(security.size()>1000)throw new IllegalStateException("SECURITY_INCIDENT_LIMIT");
            incidents.compareAndSet(snapshot.revision(),Json.write(Map.of("format",1,"incidents",security.stream().sorted(Comparator.comparing(Key::release).thenComparingLong(Key::generation)).map(x->Map.of("release",x.release(),"generation",x.generation())).toList())));
        }catch(IOException failure){throw new IllegalStateException("SECURITY_INCIDENT_PERSISTENCE_UNKNOWN",failure);}
    }
    @Override public synchronized boolean confirmedSecurityViolation(String release,long generation){Key k=new Key(release,generation);if(security.contains(k)||(buckets.containsKey(k)&&buckets.get(k).security))return true;if(incidentOverflow)throw new IllegalStateException("SECURITY_INCIDENT_LIMIT");refreshIncidents();return security.contains(k);}
    private void refreshIncidents(){if(incidents!=null)try{mergeIncidents(incidents.load().json());}catch(IOException failure){throw new IllegalStateException("SECURITY_INCIDENT_SOURCE_UNAVAILABLE",failure);}}
    private void mergeIncidents(String json){if(json==null)return;var m=Schema.map(Json.parse(json));if(!m.keySet().equals(Set.of("format","incidents"))||Schema.number(m.get("format"))!=1)throw new IllegalStateException("SECURITY_INCIDENT_FORMAT");var list=Schema.list(m.get("incidents"));if(list.size()>1000)throw new IllegalStateException("SECURITY_INCIDENT_LIMIT");for(Object raw:list){var item=Schema.map(raw);String id=Schema.str(item.get("release"));long generation=Schema.number(item.get("generation"));if(!item.keySet().equals(Set.of("release","generation"))||!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")||generation<1)throw new IllegalStateException("SECURITY_INCIDENT_FORMAT");Key key=new Key(id,generation);if(!security.contains(key)&&security.size()>=1000){incidentOverflow=true;throw new IllegalStateException("SECURITY_INCIDENT_LIMIT");}security.add(key);}}
    @Override public synchronized Window closeWindow(String release,long generation){
        Key k=new Key(release,generation);Bucket b=buckets.get(k);Instant now=clock.instant();
        if(b==null)return new Window(epoch,++sequence,now,now,false,"GENERATION_NOT_OBSERVED",new MetricsGate.Sample(now,release,generation,0,0,0,0,Double.NaN,false));
        buckets.put(k,new Bucket(now));
        boolean complete=!b.incomplete&&b.requests==b.completed&&b.durations.size()==b.completed;
        double p95=Double.NaN;if(complete&&!b.durations.isEmpty()){b.durations.sort(Long::compare);p95=b.durations.get((int)Math.ceil(.95*b.durations.size())-1)/1_000_000.0;}
        boolean violation=confirmedSecurityViolation(release,generation);
        var sample=new MetricsGate.Sample(now,release,generation,b.completed,b.errors,b.businessErrors,b.unknown,p95,violation);
        return new Window(epoch,++sequence,b.opened,now,complete,complete?"COMPLETE":"INCOMPLETE_OR_OVERFLOW",sample);
    }
}
