package fcp.release.observation;

import fcp.*;
import fcp.control.StateJournal;
import fcp.host.TrustedContext;
import fcp.release.control.*;
import java.io.IOException;
import java.time.*;
import java.util.*;

/** Explicitly enabled, durable step controller driven only by a trusted observation source.
 * tick is a current authorized USER request, not a persisted bearer token or an implicitly privileged TASK.
 * Scheduling requires a separately approved host identity adapter and is deliberately not started here.
 */
public final class DurableRolloutAutomation {
    public record Policy(List<Integer> stages,Duration minimumDwell,MetricsGate.Limits limits) {
        public Policy {stages=List.copyOf(stages);Objects.requireNonNull(limits);if(stages.isEmpty()||stages.size()>20||minimumDwell.compareTo(Duration.ofSeconds(1))<0||minimumDwell.compareTo(Duration.ofDays(1))>0)throw new IllegalArgumentException("Policy bounds");int last=0;for(int n:stages){if(n<=last||n>10000)throw new IllegalArgumentException("Increasing stages required");last=n;}if(last!=10000)throw new IllegalArgumentException("Final stage must be full");}
    }
    private record State(long revision,Map<String,Object> data){}
    private final StateJournal journal;private final ReleaseController releases;private final ObservationProvider observations;
    private final ReleaseController.Authorizer auth;private final Clock clock;
    public DurableRolloutAutomation(StateJournal journal,ReleaseController releases,ObservationProvider observations,ReleaseController.Authorizer auth,Clock clock){this.journal=Objects.requireNonNull(journal);this.releases=Objects.requireNonNull(releases);this.observations=Objects.requireNonNull(observations);this.auth=Objects.requireNonNull(auth);this.clock=Objects.requireNonNull(clock);}
    private void require(TrustedContext c,String permission){if(c.principal().kind()!=TrustedContext.Kind.USER||!clock.instant().isBefore(c.deadline()))throw new FcpException("PERMISSION_DENIED","Current user release approval required");auth.require(c,permission);}
    private State load()throws IOException {var s=journal.load();var d=s.json()==null?new LinkedHashMap<String,Object>(Map.of("format",1,"status","DISABLED","audit",new ArrayList<>())):Schema.map(Json.parse(s.json()));if(Schema.number(d.get("format"))!=1)throw new IOException("Automation format");return new State(s.revision(),d);}
    private State save(State s)throws IOException {var n=journal.compareAndSet(s.revision(),Json.write(s.data()));return new State(n.revision(),s.data());}
    private Map<String,Object> view(State s){var m=Schema.map(Json.copy(s.data()));m.put("revision",s.revision());return m;}
    private void audit(State s,TrustedContext c,String event){Schema.list(s.data().get("audit")).add(Map.of("actor",c.principal().id(),"tenant",c.tenantId(),"event",event,"at",clock.instant().toString()));}
    public synchronized Map<String,Object> snapshot(TrustedContext c)throws IOException{require(c,"fcp:release:view");return view(load());}
    public synchronized Map<String,Object> enable(TrustedContext c,long expectedRevision,Policy policy)throws IOException {
        require(c,"fcp:release:manage");State s=load();if(s.revision()!=expectedRevision)throw new IllegalStateException("AUTOMATION_REVISION_CONFLICT");if(s.data().containsKey("pending")||s.data().containsKey("securityContainment"))throw new IllegalStateException("AUTOMATION_PENDING");
        var release=releases.snapshot(c);if(!"CANARY".equals(release.get("phase"))||release.containsKey("pending"))throw new IllegalStateException("Start a confirmed manual canary first");
        if(policy.stages().get(0)<=Schema.number(release.get("basisPoints")))throw new IllegalArgumentException("Stages must exceed current canary");
        s.data().remove("blockedReason");var plan=Schema.map(release.get("plan"));s.data().put("approvedPlan",Json.copy(plan));s.data().put("id",UUID.randomUUID().toString());s.data().put("release",plan.get("candidateRelease"));s.data().put("generation",release.get("generation"));s.data().put("owner",List.of(c.tenantId(),c.principal().id()));s.data().put("policy",policyJson(policy));s.data().put("step",0);s.data().put("status","ENABLED");s.data().put("notBefore",clock.instant().plus(policy.minimumDwell()).toString());
        // Discard pre-approval observations. The first decision uses only a post-approval closed window.
        s.data().put("windowStart",observations.closeWindow(Schema.str(s.data().get("release")),Schema.number(s.data().get("generation"))).closedAt().toString());audit(s,c,"ENABLED");return view(save(s));
    }
    public synchronized Map<String,Object> disable(TrustedContext c,long expectedRevision)throws IOException{require(c,"fcp:release:manage");State s=load();if(s.revision()!=expectedRevision)throw new IllegalStateException("AUTOMATION_REVISION_CONFLICT");if(s.data().containsKey("pending"))throw new IllegalStateException("Reconcile pending release step before disabling; use release ABORT for containment");s.data().put("status","DISABLED");audit(s,c,"DISABLED");return view(save(s));}
    /** Narrow containment-only check. Never consumes a performance sample or advances a rollout.
     * The native single-owner host shares this exact ReleaseController instance, so its monitor
     * binds the confirmed pending snapshot to containment submission against concurrent completion.
     */
    public synchronized Map<String,Object> checkSafety(TrustedContext c,long expectedRevision,long expectedReleaseRevision,String pendingOperationId)throws IOException {
        require(c,"fcp:release:manage");State s=load();
        if(s.revision()!=expectedRevision)throw new IllegalStateException("AUTOMATION_REVISION_CONFLICT");
        if(!s.data().containsKey("pending"))throw new IllegalStateException("AUTOMATION_PENDING_REQUIRED");
        if(!List.of(c.tenantId(),c.principal().id()).equals(s.data().get("owner")))throw new FcpException("PERMISSION_DENIED","Policy approval belongs to another operator");
        synchronized(releases){
            var release=releases.snapshot(c);
            if(Schema.number(release.get("revision"))!=expectedReleaseRevision||!release.containsKey("pending")||!Objects.equals(Schema.map(release.get("pending")).get("operationId"),pendingOperationId))throw new IllegalStateException("CONFIRMED_RELEASE_PENDING_CHANGED");
            String approvedOperation=Schema.str(Schema.map(s.data().get("pending")).get("operationId"));
            String containmentOperation=s.data().containsKey("securityContainment")?Schema.str(Schema.map(s.data().get("securityContainment")).get("operationId")):null;
            if(!Objects.equals(pendingOperationId,approvedOperation)&&!Objects.equals(pendingOperationId,containmentOperation))throw new IllegalStateException("AUTOMATION_RELEASE_PENDING_MISMATCH");
            if(s.data().containsKey("securityContainment"))return contain(s,c,release);
            var pending=Schema.map(s.data().get("pending"));var sampled=sample(Schema.map(pending.get("sample")));
            if(!Schema.map(release.get("plan")).get("candidateRelease").equals(sampled.releaseId()))throw new IllegalStateException("CONFIRMED_RELEASE_PLAN_CHANGED");
            boolean incident;
            try{incident=pendingSecurity(s,release,sampled);}
            catch(IOException|RuntimeException unavailable){s.data().put("blockedReason","SECURITY_SOURCE_UNAVAILABLE");audit(s,c,"SAFETY_CHECK_SOURCE_UNAVAILABLE");return view(save(s));}
            if(!incident){s.data().remove("blockedReason");audit(s,c,"SAFETY_CHECK_NO_CONFIRMED_INCIDENT");return view(save(s));}
            s.data().put("securityContainment",Map.of("operationId",Schema.str(pending.get("operationId"))+"-security","release",sampled.releaseId()));
            audit(s,c,"SAFETY_CHECK_CONTAINMENT_INTENT");s=save(s);return contain(s,c,release);
        }
    }
    public synchronized Map<String,Object> tick(TrustedContext c)throws IOException {
        require(c,"fcp:release:manage");State s=load();if(!"ENABLED".equals(s.data().get("status")))return view(s);
        if(!s.data().get("owner").equals(List.of(c.tenantId(),c.principal().id())))throw new FcpException("PERMISSION_DENIED","Policy approval belongs to another operator");
        var release=releases.snapshot(c);
        if(s.data().containsKey("pending"))return finishOrApply(s,c,release);
        if(!"CANARY".equals(release.get("phase"))||release.containsKey("pending")||Schema.number(release.get("generation"))!=Schema.number(s.data().get("generation"))||!Schema.map(release.get("plan")).get("candidateRelease").equals(s.data().get("release"))){s.data().put("status","PAUSED");audit(s,c,"EXTERNAL_CHANGE_PAUSED");return view(save(s));}
        String id=Schema.str(s.data().get("release"));long generation=Schema.number(s.data().get("generation"));Policy policy=policy(Schema.map(s.data().get("policy")));
        boolean security=false,unavailable=false;
        try{security=observations.confirmedSecurityViolation(id,generation);}catch(IOException|RuntimeException failure){unavailable=true;}
        if(!security&&!unavailable&&clock.instant().isBefore(Instant.parse(Schema.str(s.data().get("notBefore")))))return view(s);
        MetricsGate.Sample sample=new MetricsGate.Sample(clock.instant(),id,generation,0,0,0,0,Double.NaN,security);
        if(!security&&!unavailable)try{
            ObservationProvider.Window w=observations.closeWindow(id,generation);MetricsGate.Sample actual=w.sample();
            boolean confirmed=actual!=null&&actual.securityViolation()&&id.equals(actual.releaseId())&&generation==actual.generation();
            if(confirmed)sample=new MetricsGate.Sample(clock.instant(),id,generation,0,0,0,0,Double.NaN,true);
            else if(actual!=null&&actual.observedAt()!=null&&w.complete()&&!w.openedAt().isBefore(Instant.parse(Schema.str(s.data().get("windowStart"))))&&Duration.between(w.openedAt(),w.closedAt()).compareTo(policy.minimumDwell())>=0&&!w.closedAt().isAfter(clock.instant()))sample=actual;
        }catch(IOException|RuntimeException failure){unavailable=true;}
        if(unavailable)audit(s,c,"OBSERVATION_UNAVAILABLE");
        int step=Math.toIntExact(Schema.number(s.data().get("step")));String op="auto-"+s.data().get("id")+"-"+step;
        s.data().put("pending",Map.of("operationId",op,"expectedReleaseRevision",release.get("revision"),"target",policy.stages().get(step),"sample",sampleJson(sample)));
        audit(s,c,"STEP_INTENT");s=save(s);return finishOrApply(s,c,release);
    }
    private Map<String,Object> finishOrApply(State s,TrustedContext c,Map<String,Object> release)throws IOException {
        var pending=Schema.map(s.data().get("pending"));String op=Schema.str(pending.get("operationId"));var operations=Schema.map(release.get("operations"));
        // A durable performance sample is not authority to ignore a later security incident.
        // Recheck even if the original command is already accepted; containment has a separate identity.
        var sampled=sample(Schema.map(pending.get("sample")));
        if(s.data().containsKey("securityContainment"))return contain(s,c,release);
        boolean security=false;
        try{security=pendingSecurity(s,release,sampled);}
        catch(IOException|RuntimeException unavailable){if(operations.containsKey(op)||MetricsGate.evaluate(sampled,policy(Schema.map(s.data().get("policy"))).limits(),clock.instant(),sampled.releaseId(),sampled.generation())==MetricsGate.Decision.PROCEED){s.data().put("blockedReason","SECURITY_SOURCE_UNAVAILABLE");audit(s,c,"RECOVERY_SECURITY_UNAVAILABLE");return view(save(s));}}
        if(security){
            s.data().put("securityContainment",Map.of("operationId",op+"-security","release",sampled.releaseId()));
            audit(s,c,"RECOVERY_SECURITY_CONTAINMENT_INTENT");s=save(s);return contain(s,c,release);
        }
        s.data().remove("blockedReason");
        if(!operations.containsKey(op)){
            if(release.containsKey("pending")||Schema.number(release.get("revision"))!=Schema.number(pending.get("expectedReleaseRevision"))){s.data().remove("pending");s.data().put("status","PAUSED");audit(s,c,"CONFLICT_PAUSED");return view(save(s));}
            // Same immutable operation ID plus release CAS: two racing callers cannot execute different steps.
            release=releases.advance(c,Schema.number(pending.get("expectedReleaseRevision")),op,Math.toIntExact(Schema.number(pending.get("target"))),sample(Schema.map(pending.get("sample"))),policy(Schema.map(s.data().get("policy"))).limits());
        }else if(release.containsKey("pending"))return view(s); // Operator reconciles the release controller; never resubmit executor work here.
        if(release.containsKey("pending"))return view(s);
        var receipt=Schema.map(Schema.map(release.get("operations")).get(op));
        if(!"COMPLETE".equals(receipt.get("status"))){s.data().remove("pending");s.data().put("status","PAUSED");audit(s,c,"FAILED_STEP_PAUSED");return view(save(s));}
        s.data().remove("pending");String phase=Schema.str(release.get("phase"));
        s.data().put("status",phase.equals("CANARY")?"ENABLED":phase.equals("FULL")?"COMPLETE":"PAUSED");s.data().put("generation",release.get("generation"));s.data().put("step",Schema.number(s.data().get("step"))+1);
        try{s.data().put("windowStart",observations.closeWindow(Schema.str(s.data().get("release")),Schema.number(s.data().get("generation"))).closedAt().toString());}
        catch(IOException|RuntimeException failure){if("ENABLED".equals(s.data().get("status")))s.data().put("status","PAUSED");s.data().put("windowStart",clock.instant().toString());audit(s,c,"OBSERVATION_RESET_UNAVAILABLE");}
        s.data().put("notBefore",clock.instant().plus(policy(Schema.map(s.data().get("policy"))).minimumDwell()).toString());audit(s,c,"OBSERVED_"+phase);return view(save(s));
    }
    /** Check both the approved sample generation and server-owned execution generations.
     * A withheld deployment receipt may leave control.generation behind the actual routed pending generation.
     * Never infer a generation from browser input or mix a different immutable release composition.
     */
    private boolean pendingSecurity(State s,Map<String,Object> release,MetricsGate.Sample sampled)throws IOException {
        var pending=Schema.map(s.data().get("pending"));String operation=Schema.str(pending.get("operationId"));
        var plan=Schema.map(release.get("plan"));Object receipt=Schema.map(release.get("operations")).get(operation);
        if(!sampled.releaseId().equals(plan.get("candidateRelease")))throw new IOException("SECURITY_RELEASE_PLAN_CHANGED");
        if(s.data().containsKey("approvedPlan")&&!s.data().get("approvedPlan").equals(plan))throw new IOException("SECURITY_RELEASE_PLAN_CHANGED");
        if(receipt!=null&&!Schema.map(Schema.map(receipt).get("command")).get("plan").equals(plan))throw new IOException("SECURITY_RELEASE_PLAN_CHANGED");
        if(!s.data().containsKey("approvedPlan")&&receipt==null&&Schema.number(release.get("revision"))!=Schema.number(pending.get("expectedReleaseRevision")))throw new IOException("SECURITY_RELEASE_PLAN_UNPROVEN");
        Set<Long> generations=new LinkedHashSet<>();generations.add(sampled.generation());
        if(receipt!=null){
            generations.add(Schema.number(release.get("generation")));
            if(release.containsKey("pending")){
                var executing=Schema.map(release.get("pending"));
                if(operation.equals(executing.get("operationId"))&&plan.equals(executing.get("plan")))generations.add(Schema.number(executing.get("generation")));
            }
        }
        IOException unavailable=null;
        for(long generation:generations)try{if(observations.confirmedSecurityViolation(sampled.releaseId(),generation))return true;}
        catch(IOException|RuntimeException failure){unavailable=new IOException("SECURITY_SOURCE_UNAVAILABLE",failure);}
        if(unavailable!=null)throw unavailable;return false;
    }
    private Map<String,Object> contain(State s,TrustedContext c,Map<String,Object> release)throws IOException {
        var intent=Schema.map(s.data().get("securityContainment"));String op=Schema.str(intent.get("operationId"));
        if(!Schema.map(release.get("plan")).get("candidateRelease").equals(intent.get("release"))){
            s.data().put("blockedReason","CONTAINMENT_REQUIRES_CURRENT_PLAN_REVIEW");return view(save(s));
        }
        var receipts=Schema.map(release.get("operations"));
        if(!receipts.containsKey(op)&&!"ABORTED".equals(release.get("phase"))){
            if(release.containsKey("pending")&&!Boolean.TRUE.equals(release.get("supportsFencedContainment"))){
                s.data().put("blockedReason","CONTAINMENT_REQUIRED_PROVIDER_NOT_FENCED");audit(s,c,"CONTAINMENT_BLOCKED");return view(save(s));
            }
            release=releases.execute(c,Schema.number(release.get("revision")),op,DeploymentProvider.Action.ABORT,0);
        }
        if(release.containsKey("pending")||!"ABORTED".equals(release.get("phase"))||!"STOPPED".equals(Schema.map(release.get("observed")).get("routingMode"))){
            s.data().put("blockedReason","CONTAINMENT_AWAITING_OBSERVED_STOP");return view(save(s));
        }
        s.data().put("lastSecurityContainment",Map.of("intent",Json.copy(intent),"observedGeneration",release.get("generation"),"completedAt",clock.instant().toString()));
        Schema.list(s.data().get("audit")).add(Map.of("actor",c.principal().id(),"tenant",c.tenantId(),"event","SECURITY_CONTAINMENT_ARCHIVED","operationId",op,"release",intent.get("release"),"observedGeneration",release.get("generation"),"at",clock.instant().toString()));
        s.data().remove("securityContainment");s.data().remove("pending");s.data().remove("blockedReason");s.data().put("status","PAUSED");audit(s,c,"SECURITY_CONTAINMENT_OBSERVED");return view(save(s));
    }
    private static Map<String,Object> policyJson(Policy p){return Map.of("stages",p.stages(),"dwellMillis",p.minimumDwell().toMillis(),"minimumSamples",p.limits().minimumSamples(),"maximumAgeMillis",p.limits().maximumAge().toMillis(),"maximumErrorRate",p.limits().maximumErrorRate(),"maximumP95Millis",p.limits().maximumP95Millis());}
    private static Policy policy(Map<String,Object> m){return new Policy(Schema.list(m.get("stages")).stream().map(x->Math.toIntExact(Schema.number(x))).toList(),Duration.ofMillis(Schema.number(m.get("dwellMillis"))),new MetricsGate.Limits(Schema.number(m.get("minimumSamples")),Duration.ofMillis(Schema.number(m.get("maximumAgeMillis"))),((Number)m.get("maximumErrorRate")).doubleValue(),((Number)m.get("maximumP95Millis")).doubleValue()));}
    private static Map<String,Object> sampleJson(MetricsGate.Sample s){return Map.of("at",s.observedAt().toString(),"release",s.releaseId(),"generation",s.generation(),"requests",s.requests(),"technical",s.technicalErrors(),"business",s.businessErrors(),"unknown",s.unknownResults(),"p95",Double.toString(s.p95Millis()),"security",s.securityViolation());}
    private static MetricsGate.Sample sample(Map<String,Object> m){return new MetricsGate.Sample(Instant.parse(Schema.str(m.get("at"))),Schema.str(m.get("release")),Schema.number(m.get("generation")),Schema.number(m.get("requests")),Schema.number(m.get("technical")),Schema.number(m.get("business")),Schema.number(m.get("unknown")),Double.parseDouble(Schema.str(m.get("p95"))),(Boolean)m.get("security"));}
}
