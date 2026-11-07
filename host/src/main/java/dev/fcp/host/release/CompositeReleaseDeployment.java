package dev.fcp.host.release;

import fcp.*;
import fcp.control.StateJournal;
import fcp.release.control.*;
import fcp.release.routing.*;
import java.io.IOException;
import java.util.*;

/** Single-host composite executor. Components must enforce live host permissions and exact immutable composition.
 * The router is the SAME object used by UI bootstrap, plugin catalog, invocation and storage commit fences.
 */
public final class CompositeReleaseDeployment implements DeploymentProvider {
    public record Status(boolean ready, boolean stopped, long activeCalls, long pendingTasks) {
        public Status { if(activeCalls<0||pendingTasks<0)throw new IllegalArgumentException("Invalid runtime status"); }
    }
    public interface Components {
        void verify(ReleasePlan plan) throws IOException;
        void prepare(String releaseId) throws IOException;
        Status status(String releaseId) throws IOException;
        /** Close new requests/pins; already pinned tasks can finish on their original version. */
        void beginDrain(String releaseId) throws IOException;
        void stop(String releaseId) throws IOException;
    }
    @FunctionalInterface public interface GenerationObserver { void bind(String release,long generation,long routerRevision); }
    private record State(long revision,Map<String,Object> data) {}
    private static final Map<DurableReleaseRouter,Object> OWNER_LOCKS=Collections.synchronizedMap(new WeakHashMap<>());
    private final Object ownerLock;
    private final StateJournal journal;
    private final DurableReleaseRouter router;
    private final Components components;
    private final String environment;
    private final GenerationObserver observer;
    public CompositeReleaseDeployment(StateJournal journal,DurableReleaseRouter router,Components components,String environment,GenerationObserver observer) {
        this.journal=Objects.requireNonNull(journal);this.router=Objects.requireNonNull(router);this.components=Objects.requireNonNull(components);
        synchronized(OWNER_LOCKS){ownerLock=OWNER_LOCKS.computeIfAbsent(router,k->new Object());}
        if(environment==null||!environment.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))throw new IllegalArgumentException("Invalid environment");
        this.environment=environment;this.observer=Objects.requireNonNull(observer);
    }
    public Set<Action> capabilities(){return Set.of(Action.values());}
    public boolean supportsRecovery(){return true;}
    public boolean supportsFencedContainment(){return true;}
    private State load()throws IOException {
        var s=journal.load();Map<String,Object> data=s.json()==null?new LinkedHashMap<>(Map.of("format",1,"fence",0,"operations",new LinkedHashMap<>())):Schema.map(Json.parse(s.json()));
        if(Schema.number(data.get("format"))!=1)throw new IOException("COMPOSITE_STATE_FORMAT");return new State(s.revision(),data);
    }
    private State save(State state)throws IOException {var s=journal.compareAndSet(state.revision(),Json.write(state.data()));return new State(s.revision(),state.data());}
    private Map<String,Object> ops(State s){return Schema.map(s.data().get("operations"));}
    private Map<String,Object> receipt(State s,String id){Object r=ops(s).get(id);return r==null?null:Schema.map(r);}
    private void check(Command command)throws IOException {if(!environment.equals(command.plan().environment()))throw new IOException("COMPOSITE_ENVIRONMENT_MISMATCH");}
    private void same(Map<String,Object> r,Command c)throws IOException {if(!c.digest().equals(r.get("digest")))throw new IOException("COMPOSITE_OPERATION_CONFLICT");}
    private void fence(State s,Command c)throws IOException {if(Schema.number(s.data().get("fence"))!=c.generation())throw new IOException("COMPOSITE_OPERATION_SUPERSEDED");}
    public RecoveryDisposition recovery(Command c)throws IOException {synchronized(ownerLock){return recoveryLocked(c);}}
    public void submit(Command c)throws IOException {synchronized(ownerLock){submitLocked(c);}}
    public Optional<Observation> observe(String env,String operation)throws IOException {synchronized(ownerLock){return observeLocked(env,operation);}}
    private RecoveryDisposition recoveryLocked(Command command)throws IOException {
        check(command);State s=load();var r=receipt(s,command.operationId());
        if(r!=null){same(r,command);return RecoveryDisposition.ACCEPTED;}
        long generation=Schema.number(s.data().get("fence"));
        return command.generation()==generation+1||command.action()==Action.ABORT&&command.generation()>generation?RecoveryDisposition.NOT_ACCEPTED:RecoveryDisposition.UNKNOWN;
    }
    public void resumeNotAccepted(Command command)throws IOException {
        synchronized(ownerLock){if(recoveryLocked(command)!=RecoveryDisposition.NOT_ACCEPTED)throw new IOException("COMPOSITE_RECOVERY_NOT_PROVEN");submitLocked(command);}
    }
    private void submitLocked(Command command)throws IOException {
        check(command);State s=load();var prior=receipt(s,command.operationId());if(prior!=null){same(prior,command);return;}
        long currentGeneration=Schema.number(s.data().get("fence"));long next=Math.addExact(currentGeneration,1);
        if(command.generation()!=next&&!(command.action()==Action.ABORT&&command.generation()>currentGeneration))throw new IOException("COMPOSITE_GENERATION_CONFLICT");
        boolean pending=ops(s).values().stream().anyMatch(v->Set.of("INTENT","WAIT_DRAIN","STOP_INTENT").contains(Schema.map(v).get("phase")));
        if(pending&&command.action()!=Action.ABORT)throw new IOException("COMPOSITE_OPERATION_PENDING");
        if(command.action()==Action.ABORT)for(Object raw:ops(s).values()){var r=Schema.map(raw);if(Set.of("INTENT","WAIT_DRAIN","STOP_INTENT").contains(r.get("phase")))r.put("phase","SUPERSEDED");}
        var r=new LinkedHashMap<String,Object>();r.put("command",command.json());r.put("digest",command.digest());r.put("phase","INTENT");
        ops(s).put(command.operationId(),r);s.data().put("fence",command.generation());s=save(s);
        if(command.action()!=Action.ABORT&&command.action()!=Action.PAUSE&&command.action()!=Action.RETIRE) {
            try{components.verify(command.plan());}catch(IOException|RuntimeException failure){failed(command);return;}
        }
        if(command.action()==Action.DEPLOY){components.prepare(command.plan().stableRelease());components.prepare(command.plan().candidateRelease());}
        if(command.action()==Action.ROLLBACK)components.prepare(command.plan().stableRelease());
        if(command.action()==Action.RETIRE){beginRetire(command);return;}
        if(command.action()!=Action.ABORT&&command.action()!=Action.PAUSE) {
            boolean ready;
            try{ready=components.status(command.plan().stableRelease()).ready()&&(command.action()==Action.ROLLBACK||components.status(command.plan().candidateRelease()).ready());}
            catch(IOException|RuntimeException e){if(command.action()==Action.DEPLOY)throw e;failed(command);return;}
            if(!ready){if(command.action()==Action.DEPLOY)throw new IOException("COMPOSITE_PREPARE_OUTCOME_UNKNOWN");failed(command);return;}
        }
        applyRoute(command);
    }
    private void failed(Command c)throws IOException {State s=load();fence(s,c);receipt(s,c.operationId()).put("phase","FAILED");save(s);}
    private void applyRoute(Command c)throws IOException {
        State s=load();fence(s,c);var previous=router.policy().orElse(null);
        long epoch=previous==null?0:previous.epoch();
        if(c.action()==Action.ABORT||c.action()==Action.ROLLBACK||previous!=null&&(!previous.stableRelease().equals(c.plan().stableRelease())||!previous.candidateRelease().equals(c.plan().candidateRelease())))epoch=Math.addExact(epoch,1);
        boolean enabled=c.action()!=Action.ABORT&&(c.action()!=Action.PAUSE||previous!=null&&previous.enabled());
        int bp=c.action()==Action.PAUSE&&previous!=null?previous.basisPoints():c.basisPoints();
        long revision=router.revision();var r=receipt(s,c.operationId());r.put("expectedRouterRevision",revision);r.put("targetEpoch",epoch);r.put("targetEnabled",enabled);r.put("targetBasisPoints",bp);s=save(s);
        // The native owner lease and shared executor owner monitor cover the complete effect. No distributed executor claim.
        State current=load();fence(current,c);
        long applied=router.put(new ReleasePolicy("native-"+environment,c.plan().stableRelease(),c.plan().candidateRelease(),bp,Set.of(),Set.of(),enabled,epoch),revision);
        State done=load();fence(done,c);var entry=receipt(done,c.operationId());entry.put("phase","COMPLETE");entry.put("routerRevision",applied);save(done);
        bindObserved(c,applied);

    }
    private void bindObserved(Command c,long revision) {
        // Missing/failed telemetry must pause rollout, never prevent emergency containment.
        try{observer.bind(c.plan().stableRelease(),c.generation(),revision);observer.bind(c.plan().candidateRelease(),c.generation(),revision);}catch(RuntimeException unavailable){}
    }
    private void reconcileEffect(Command c,Map<String,Object> r)throws IOException {
        String phase=Schema.str(r.get("phase"));
        if(phase.equals("STOP_INTENT")) {
            for(Object target:Schema.list(r.get("targets"))){Status st=components.status(Schema.str(target));if(!st.stopped()||st.activeCalls()!=0||st.pendingTasks()!=0)return;}
            State s=load();fence(s,c);receipt(s,c.operationId()).put("phase","COMPLETE");save(s);return;
        }
        if(!phase.equals("INTENT")||!r.containsKey("expectedRouterRevision"))return;
        var p=router.policy().orElse(null);long revision=router.revision();
        if(p!=null&&revision==Schema.number(r.get("expectedRouterRevision"))+1&&p.stableRelease().equals(c.plan().stableRelease())&&p.candidateRelease().equals(c.plan().candidateRelease())&&p.epoch()==Schema.number(r.get("targetEpoch"))&&p.basisPoints()==Schema.number(r.get("targetBasisPoints"))&&p.enabled()==Boolean.TRUE.equals(r.get("targetEnabled"))) {
            State s=load();fence(s,c);var entry=receipt(s,c.operationId());entry.put("phase","COMPLETE");entry.put("routerRevision",revision);save(s);bindObserved(c,revision);
        }
    }
    /** Restore generation association only, never replay deployment or fabricate request samples. */
    public void restoreObservationBinding()throws IOException {
        synchronized(ownerLock){
            var policy=router.policy();if(policy.isEmpty())return;
            long revision=router.revision();State s=load();
            for(Object raw:ops(s).values()) {
                var r=Schema.map(raw);if(!Set.of("COMPLETE","INTENT").contains(r.get("phase"))||!r.containsKey("expectedRouterRevision"))continue;
                Command c=parse(Schema.map(r.get("command")));var p=policy.get();
                if(revision==Schema.number(r.get("expectedRouterRevision"))+1&&p.stableRelease().equals(c.plan().stableRelease())&&p.candidateRelease().equals(c.plan().candidateRelease())&&p.epoch()==Schema.number(r.get("targetEpoch"))&&p.basisPoints()==Schema.number(r.get("targetBasisPoints"))&&p.enabled()==Boolean.TRUE.equals(r.get("targetEnabled"))) {
                    observer.bind(c.plan().stableRelease(),c.generation(),revision);observer.bind(c.plan().candidateRelease(),c.generation(),revision);return;
                }
            }
            throw new IOException("NATIVE_ROUTE_GENERATION_REQUIRES_RECONCILIATION");
        }
    }
    private List<String> retireTargets(Command c)throws IOException {
        var p=router.policy().orElseThrow(()->new IOException("COMPOSITE_ROUTE_MISSING"));
        if(!p.stableRelease().equals(c.plan().stableRelease())||!p.candidateRelease().equals(c.plan().candidateRelease()))throw new IOException("COMPOSITE_ROUTE_COMPOSITION_MISMATCH");
        if(!p.enabled())return List.of(p.stableRelease(),p.candidateRelease());
        if(p.basisPoints()==10000)return List.of(p.stableRelease());
        if(p.basisPoints()==0)return List.of(p.candidateRelease());
        throw new IOException("COMPOSITE_RETIRE_ACTIVE_VERSION");
    }
    private void beginRetire(Command c)throws IOException {
        List<String> targets=retireTargets(c);State s=load();fence(s,c);receipt(s,c.operationId()).put("targets",targets);s=save(s);
        for(String target:targets)components.beginDrain(target);
        State wait=load();fence(wait,c);receipt(wait,c.operationId()).put("phase","WAIT_DRAIN");save(wait);finishRetire(c);
    }
    private void finishRetire(Command c)throws IOException {
        State s=load();fence(s,c);var r=receipt(s,c.operationId());
        if(!"WAIT_DRAIN".equals(r.get("phase")))return;
        List<String> targets=Schema.list(r.get("targets")).stream().map(Schema::str).toList();
        for(String target:targets){Status status=components.status(target);if(status.activeCalls()!=0||status.pendingTasks()!=0)return;}
        r.put("phase","STOP_INTENT");save(s);
        for(String target:targets)components.stop(target);
        State done=load();fence(done,c);receipt(done,c.operationId()).put("phase","COMPLETE");save(done);
    }
    private Optional<Observation> observeLocked(String env,String operation)throws IOException {
        if(!environment.equals(env))throw new IOException("COMPOSITE_ENVIRONMENT_MISMATCH");State s=load();var r=receipt(s,operation);if(r==null)return Optional.empty();
        Command c=parse(Schema.map(r.get("command")));reconcileEffect(c,r);s=load();r=receipt(s,operation);String phase=Schema.str(r.get("phase"));
        if(phase.equals("WAIT_DRAIN")){finishRetire(c);s=load();r=receipt(s,operation);phase=Schema.str(r.get("phase"));}
        var p=router.policy().orElse(null);boolean stopped=p!=null&&!p.enabled();int bp=p==null?0:p.basisPoints();
        RoutingMode mode=stopped?RoutingMode.STOPPED:RoutingMode.ACTIVE;
        boolean complete=phase.equals("COMPLETE")||phase.equals("FAILED")||phase.equals("SUPERSEDED");
        boolean success=phase.equals("COMPLETE");
        long active=0;boolean healthy=true;
        if(phase.equals("FAILED")||phase.equals("SUPERSEDED")){healthy=false;}
        else if(c.action()==Action.RETIRE){
            for(Object raw:Schema.list(r.getOrDefault("targets",List.of()))){Status st=components.status(Schema.str(raw));active=Math.addExact(active,st.activeCalls());if(success&&(!st.stopped()||st.pendingTasks()!=0))complete=false;}
        }else if(c.action()!=Action.ABORT){
            for(String id:List.of(c.plan().stableRelease(),c.plan().candidateRelease())){Status st=components.status(id);active=Math.addExact(active,st.activeCalls());healthy&=st.ready()||(c.action()==Action.ROLLBACK&&id.equals(c.plan().candidateRelease()));}
        }else healthy=stopped;
        if(success&&c.action()!=Action.RETIRE){
            boolean actual=p!=null&&p.stableRelease().equals(c.plan().stableRelease())&&p.candidateRelease().equals(c.plan().candidateRelease())&&p.basisPoints()==Schema.number(r.get("targetBasisPoints"))&&p.enabled()==Boolean.TRUE.equals(r.get("targetEnabled"))&&p.epoch()==Schema.number(r.get("targetEpoch"))&&router.revision()==Schema.number(r.get("routerRevision"));
            if(!actual){complete=false;healthy=false;}
        }
        return Optional.of(new Observation(operation,complete,healthy,c.plan().candidateRelease(),c.plan().candidateDigest(),bp,c.generation(),active,
            success?"NATIVE_COMPOSITION_OBSERVED":phase,mode,complete?(success?ExecutionOutcome.SUCCEEDED:ExecutionOutcome.FAILED):ExecutionOutcome.PENDING));
    }
    private static Command parse(Map<String,Object> m){return new Command(Schema.str(m.get("operationId")),ReleasePlan.parse(Schema.map(m.get("plan"))),Action.valueOf(Schema.str(m.get("action"))),Math.toIntExact(Schema.number(m.get("basisPoints"))),Schema.number(m.get("generation")));}
}
