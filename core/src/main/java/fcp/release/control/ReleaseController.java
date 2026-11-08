package fcp.release.control;

import fcp.*;
import fcp.control.StateJournal;
import fcp.host.TrustedContext;
import java.io.IOException;
import java.time.*;
import java.util.*;
import static fcp.release.control.DeploymentProvider.Action;

/** Durable single-environment release coordinator. StateJournal supplies atomic CAS, not deployment transactions. */
public final class ReleaseController {
    @FunctionalInterface public interface Authorizer { void require(TrustedContext context, String permission); }
    /** Must independently verify artifact provenance/digests and actual database schema/dirty state. */
    @FunctionalInterface public interface Preflight { void verify(ReleasePlan plan) throws IOException; }
    private final StateJournal journal;
    private final DeploymentProvider provider;
    private final Authorizer authorizer;
    private final Preflight preflight;
    private final Clock clock;
    public ReleaseController(StateJournal journal, DeploymentProvider provider, Authorizer authorizer, Preflight preflight, Clock clock) {
        this.journal=Objects.requireNonNull(journal);this.provider=Objects.requireNonNull(provider);
        this.authorizer=Objects.requireNonNull(authorizer);this.preflight=Objects.requireNonNull(preflight);this.clock=Objects.requireNonNull(clock);
    }
    private record Loaded(long revision,Map<String,Object> data){}
    private Loaded load()throws IOException {
        StateJournal.Snapshot snap=journal.load();
        Map<String,Object> d=snap.json()==null?new LinkedHashMap<>(Map.of("format",1,"phase","EMPTY","basisPoints",0,"generation",0,"operations",new LinkedHashMap<>(),"audit",new ArrayList<>())):Schema.map(Json.parse(snap.json()));
        if(Schema.number(d.get("format"))!=1)throw new IOException("Unsupported release state");
        return new Loaded(snap.revision(),d);
    }
    private void access(TrustedContext ctx,String permission){Objects.requireNonNull(ctx);if(!ctx.deadline().isAfter(clock.instant()))throw new IllegalStateException("CONTEXT_EXPIRED");authorizer.require(ctx,permission);}
    public synchronized Map<String,Object> snapshot(TrustedContext ctx)throws IOException {access(ctx,"fcp:release:view");return view(load());}
    private Map<String,Object> view(Loaded l){Map<String,Object> out=Schema.map(Json.copy(l.data()));out.put("revision",l.revision());out.put("supportsRecovery",provider.supportsRecovery());out.put("supportsFencedContainment",provider.supportsFencedContainment());out.put("capabilities",provider.capabilities().stream().map(Enum::name).sorted().toList());return out;}
    private Loaded save(Loaded l)throws IOException {StateJournal.Snapshot s=journal.compareAndSet(l.revision(),Json.write(l.data()));return new Loaded(s.revision(),l.data());}
    private void revision(Loaded l,long expected){if(l.revision()!=expected)throw new IllegalStateException("RELEASE_REVISION_CONFLICT");}
    private void audit(Loaded l,TrustedContext ctx,String event,String op){List<Object>a=Schema.list(l.data().get("audit"));a.add(Map.of("actor",ctx.principal().id(),"actorKind",ctx.principal().kind().name(),"tenant",ctx.tenantId(),"traceId",ctx.traceId(),"at",clock.instant().toString(),"event",event,"operationId",op));}
    private Map<String,Object> operations(Loaded l){return Schema.map(l.data().get("operations"));}
    private boolean replay(Loaded l,String op,Map<String,Object> command){Object old=operations(l).get(op);if(old==null)return false;if(!Schema.map(Schema.map(old).get("command")).equals(Json.copy(command)))throw new IllegalStateException("OPERATION_ID_CONFLICT");return true;}
    public synchronized Map<String,Object> createPlan(TrustedContext ctx,long expected,String operationId,ReleasePlan plan)throws IOException {
        access(ctx,"fcp:release:manage");new DeploymentProvider.Command(operationId,plan,Action.DEPLOY,0,1);
        Loaded l=load();Map<String,Object> command=Map.of("action","PLAN","plan",plan.json(),"actor",ctx.principal().id(),"tenant",ctx.tenantId());
        if(replay(l,operationId,command))return view(l);revision(l,expected);
        if(l.data().containsKey("pending") || !Set.of("EMPTY","RETIRED").contains(l.data().get("phase")))throw new IllegalStateException("RELEASE_ALREADY_ACTIVE");
        if(l.data().containsKey("plan") && !Schema.map(l.data().get("plan")).get("environment").equals(plan.environment()))throw new IllegalStateException("JOURNAL_ENVIRONMENT_MISMATCH");
        if(l.data().containsKey("currentRelease") && (!plan.stableRelease().equals(l.data().get("currentRelease")) || !plan.stableDigest().equals(l.data().get("currentDigest"))))throw new IllegalStateException("STABLE_BASELINE_MISMATCH");
        preflight.verify(plan);plan.requireCompatibleSchema(plan.schemaVersion(),false);
        l.data().put("plan",plan.json());l.data().put("phase","PLANNED");l.data().put("basisPoints",0);l.data().remove("observed");
        operations(l).put(operationId,Map.of("command",command,"status","COMPLETE"));audit(l,ctx,"PLAN",operationId);return view(save(l));
    }
    public synchronized Map<String,Object> execute(TrustedContext ctx,long expected,String operationId,Action action,int basisPoints)throws IOException {
        access(ctx,"fcp:release:manage");Loaded l=load();ReleasePlan plan=ReleasePlan.parse(Schema.map(l.data().get("plan")));
        Map<String,Object> identity=Map.of("action",action.name(),"basisPoints",basisPoints,"plan",plan.json(),"actor",ctx.principal().id(),"tenant",ctx.tenantId());
        if(replay(l,operationId,identity))return view(l);revision(l,expected);
        boolean supersede=l.data().containsKey("pending") && action==Action.ABORT && provider.supportsFencedContainment();
        if(l.data().containsKey("pending")&&!supersede)throw new IllegalStateException("RELEASE_OPERATION_PENDING");
        if(!provider.capabilities().contains(action))throw new IllegalStateException("DEPLOYMENT_CAPABILITY_UNSUPPORTED");
        if(!supersede)validate(l,action,basisPoints);else if(basisPoints!=0)throw new IllegalStateException("ABORT_REQUIRES_ZERO_TRAFFIC");
        if(action!=Action.PAUSE&&action!=Action.ABORT)preflight.verify(plan);
        long priorGeneration=Schema.number(l.data().get("generation"));
        if(supersede){Map<String,Object> old=Schema.map(l.data().get("pending"));priorGeneration=Math.max(priorGeneration,Schema.number(old.get("generation")));String oldId=Schema.str(old.get("operationId"));Map<String,Object> receipt=new LinkedHashMap<>(Schema.map(operations(l).get(oldId)));receipt.put("status","SUPERSEDED_BY_FENCED_ABORT");operations(l).put(oldId,receipt);audit(l,ctx,"FENCED_CONTAINMENT",oldId);}
        long generation=Math.addExact(priorGeneration,1);
        DeploymentProvider.Command command=new DeploymentProvider.Command(operationId,plan,action,basisPoints,generation);
        String routingMode=action==Action.ABORT?"STOPPED":(action==Action.PAUSE||action==Action.RETIRE)&&l.data().containsKey("observed")?Schema.str(Schema.map(l.data().get("observed")).get("routingMode")):"ACTIVE";
        l.data().put("pending",command.json());l.data().put("desired",Map.of("action",action.name(),"basisPoints",basisPoints,"generation",generation,"routingMode",routingMode));
        operations(l).put(operationId,Map.of("command",identity,"status","PENDING"));audit(l,ctx,"SUBMIT_INTENT",operationId);l=save(l);
        try{provider.submit(command);}catch(IOException | RuntimeException e){audit(l,ctx,"OUTCOME_UNKNOWN",operationId);save(l);throw new IOException("DEPLOYMENT_OUTCOME_UNKNOWN; reconcile operation "+operationId,e);}
        return reconcile(ctx);
    }
    private void validate(Loaded l,Action a,int bp){
        String phase=Schema.str(l.data().get("phase"));int current=Math.toIntExact(Schema.number(l.data().get("basisPoints")));
        boolean ok=switch(a){
            case DEPLOY -> phase.equals("PLANNED")&&bp==0;
            case CANARY -> phase.equals("READY")&&bp>0&&bp<10000;
            case EXPAND -> Set.of("CANARY","PAUSED").contains(phase)&&bp>current&&bp<10000;
            case PAUSE -> Set.of("CANARY","READY","FAILED","FULL").contains(phase)&&bp==current;
            case ABORT -> Set.of("READY","CANARY","PAUSED","FAILED","FULL").contains(phase)&&bp==0;
            case PROMOTE -> Set.of("CANARY","PAUSED").contains(phase)&&bp==10000;
            case ROLLBACK -> Set.of("FULL","CANARY","PAUSED","ABORTED").contains(phase)&&bp==0;
            case RETIRE -> Set.of("FULL","ABORTED","ROLLED_BACK").contains(phase)&&bp==current;
        };if(!ok)throw new IllegalStateException("INVALID_RELEASE_TRANSITION");
    }
    public synchronized Map<String,Object> reconcile(TrustedContext ctx)throws IOException {
        access(ctx,"fcp:release:manage");Loaded l=load();if(!l.data().containsKey("pending"))return view(l);
        Map<String,Object> p=Schema.map(l.data().get("pending"));String op=Schema.str(p.get("operationId"));
        ReleasePlan plan=ReleasePlan.parse(Schema.map(p.get("plan")));Optional<DeploymentProvider.Observation> result=provider.observe(plan.environment(),op);
        if(result.isEmpty())return view(l);DeploymentProvider.Observation o=result.get();
        if(!o.operationId().equals(op)||!o.candidateRelease().equals(plan.candidateRelease())||!o.candidateDigest().equals(plan.candidateDigest())||o.generation()!=Schema.number(p.get("generation")))throw new IOException("DEPLOYMENT_OBSERVATION_IDENTITY_MISMATCH");
        l.data().put("observed",o.json());
        Action action=Action.valueOf(Schema.str(p.get("action")));
        if(!o.complete())return view(save(l));
        boolean contained=action==Action.ABORT||action==Action.PAUSE;
        if(o.outcome()==DeploymentProvider.ExecutionOutcome.FAILED || (!o.healthy()&&!contained)||o.basisPoints()!=Schema.number(p.get("basisPoints")) || !o.routingMode().name().equals(Schema.str(Schema.map(l.data().get("desired")).getOrDefault("routingMode",action==Action.ABORT?"STOPPED":"ACTIVE")))){
            l.data().put("phase","FAILED");l.data().put("generation",o.generation());l.data().put("basisPoints",o.basisPoints());l.data().remove("pending");
            Map<String,Object> failed=new LinkedHashMap<>(Schema.map(operations(l).get(op)));failed.put("status","FAILED");operations(l).put(op,failed);audit(l,ctx,"OBSERVED_FAILED",op);return view(save(l));
        }
        if(action==Action.RETIRE&&o.inFlight()!=0)return view(save(l));
        String phase=switch(action){case DEPLOY->"READY";case CANARY,EXPAND->"CANARY";case PAUSE->"PAUSED";case ABORT->"ABORTED";case PROMOTE->"FULL";case ROLLBACK->"ROLLED_BACK";case RETIRE->"RETIRED";};
        if(action==Action.PROMOTE){l.data().put("currentRelease",plan.candidateRelease());l.data().put("currentDigest",plan.candidateDigest());}
        if(action==Action.ROLLBACK || action==Action.DEPLOY){l.data().put("currentRelease",plan.stableRelease());l.data().put("currentDigest",plan.stableDigest());}
        l.data().put("phase",phase);l.data().put("basisPoints",o.basisPoints());l.data().put("generation",o.generation());l.data().remove("pending");
        Map<String,Object> entry=Schema.map(operations(l).get(op));entry=new LinkedHashMap<>(entry);entry.put("status","COMPLETE");operations(l).put(op,entry);audit(l,ctx,"OBSERVED_"+phase,op);return view(save(l));
    }
    /** Explicit recovery only with authoritative absence plus atomic executor claim. A normal 404 is UNKNOWN. */
    public synchronized Map<String,Object> recover(TrustedContext ctx,long expected)throws IOException {
        access(ctx,"fcp:release:manage");Loaded l=load();revision(l,expected);if(!l.data().containsKey("pending"))return view(l);
        Map<String,Object> p=Schema.map(l.data().get("pending"));
        DeploymentProvider.Command c=new DeploymentProvider.Command(Schema.str(p.get("operationId")),ReleasePlan.parse(Schema.map(p.get("plan"))),Action.valueOf(Schema.str(p.get("action"))),Math.toIntExact(Schema.number(p.get("basisPoints"))),Schema.number(p.get("generation")));
        if(!provider.supportsRecovery())throw new IOException("SAFE_RECOVERY_UNSUPPORTED");
        DeploymentProvider.RecoveryDisposition disposition=provider.recovery(c);
        if(disposition==DeploymentProvider.RecoveryDisposition.ACCEPTED)return reconcile(ctx);
        if(disposition==DeploymentProvider.RecoveryDisposition.UNKNOWN)return view(l);
        if(c.action()!=Action.PAUSE&&c.action()!=Action.ABORT)preflight.verify(c.plan());
        audit(l,ctx,"RESUME_CONFIRMED_NOT_ACCEPTED",c.operationId());save(l);
        provider.resumeNotAccepted(c);return reconcile(ctx);
    }
    /** Automatic operations use the same authorized, persistent execution path as manual operations. */
    public synchronized Map<String,Object> advance(TrustedContext ctx,long expected,String operationId,int nextBasisPoints,MetricsGate.Sample sample,MetricsGate.Limits limits)throws IOException {
        access(ctx,"fcp:release:manage");Loaded l=load();revision(l,expected);ReleasePlan p=ReleasePlan.parse(Schema.map(l.data().get("plan")));
        MetricsGate.Decision decision=MetricsGate.evaluate(sample,limits,clock.instant(),p.candidateRelease(),Schema.number(l.data().get("generation")));
        if(decision==MetricsGate.Decision.ABORT)return execute(ctx,expected,operationId,Action.ABORT,0);
        if(decision==MetricsGate.Decision.PAUSE){if("PAUSED".equals(l.data().get("phase")))return view(l);return execute(ctx,expected,operationId,Action.PAUSE,Math.toIntExact(Schema.number(l.data().get("basisPoints"))));}
        return execute(ctx,expected,operationId,nextBasisPoints==10000?Action.PROMOTE:Action.EXPAND,nextBasisPoints);
    }
}
