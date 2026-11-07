package fcp.release.control;

import java.io.IOException;
import fcp.Json;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Infrastructure adapter. submit is idempotent; controller NEVER retries an uncertain submit. */
public interface DeploymentProvider {
    enum Action { DEPLOY, CANARY, EXPAND, PAUSE, ABORT, PROMOTE, ROLLBACK, RETIRE }
    Set<Action> capabilities();
    enum RecoveryDisposition { NOT_ACCEPTED, ACCEPTED, UNKNOWN }
    default boolean supportsRecovery(){return false;}
    default boolean supportsFencedContainment(){return false;}
    /** NOT_ACCEPTED must be authoritative, not a missing/expired receipt. */
    default RecoveryDisposition recovery(Command command)throws IOException{return RecoveryDisposition.UNKNOWN;}
    /** Atomically claim only if not accepted; duplicate identical claims have one effect. */
    default void resumeNotAccepted(Command command)throws IOException{throw new IOException("SAFE_RECOVERY_UNSUPPORTED");}

    record Command(String operationId, ReleasePlan plan, Action action, int basisPoints, long generation) {
        public Command { Objects.requireNonNull(plan); Objects.requireNonNull(action);
            if(operationId == null || !operationId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}") || basisPoints<0 || basisPoints>10000 || generation<1) throw new IllegalArgumentException("Invalid deployment command"); }
        public Map<String,Object> json(){return Map.of("operationId",operationId,"plan",plan.json(),"action",action.name(),"basisPoints",basisPoints,"generation",generation);}
        public String digest(){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.write(canonical(json())).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
        private static Object canonical(Object x){if(x instanceof Map<?,?> m){Map<String,Object> sorted=new TreeMap<>();m.forEach((k,v)->sorted.put((String)k,canonical(v)));return sorted;}if(x instanceof List<?> l)return l.stream().map(Command::canonical).toList();return x;}

    }
    enum RoutingMode { ACTIVE, STOPPED }
    enum ExecutionOutcome { PENDING, SUCCEEDED, FAILED }
    record Observation(String operationId, boolean complete, boolean healthy, String candidateRelease,
                       String candidateDigest, int basisPoints, long generation, long inFlight, String detail, RoutingMode routingMode, ExecutionOutcome outcome) {
        /** Legacy implementations do not prove closed ingress, and therefore cannot complete ABORT. */
        public Observation(String operationId,boolean complete,boolean healthy,String candidateRelease,String candidateDigest,int basisPoints,long generation,long inFlight,String detail){this(operationId,complete,healthy,candidateRelease,candidateDigest,basisPoints,generation,inFlight,detail,RoutingMode.ACTIVE);}
        public Observation(String operationId,boolean complete,boolean healthy,String candidateRelease,String candidateDigest,int basisPoints,long generation,long inFlight,String detail,RoutingMode routingMode){this(operationId,complete,healthy,candidateRelease,candidateDigest,basisPoints,generation,inFlight,detail,routingMode,complete?ExecutionOutcome.SUCCEEDED:ExecutionOutcome.PENDING);}
        public Observation { Objects.requireNonNull(outcome);if(complete==(outcome==ExecutionOutcome.PENDING))throw new IllegalArgumentException("Inconsistent execution outcome");Objects.requireNonNull(routingMode);Objects.requireNonNull(operationId); Objects.requireNonNull(candidateRelease); Objects.requireNonNull(candidateDigest); Objects.requireNonNull(detail);
            if(basisPoints<0 || basisPoints>10000 || generation<0 || inFlight<0 || detail.length()>256)throw new IllegalArgumentException("Invalid observation"); }
        public Map<String,Object> json(){Map<String,Object> m=new LinkedHashMap<>(Map.of("operationId",operationId,"complete",complete,"healthy",healthy,"candidateRelease",candidateRelease,"candidateDigest",candidateDigest,"basisPoints",basisPoints,"generation",generation,"inFlight",inFlight,"detail",detail,"routingMode",routingMode.name()));m.put("outcome",outcome.name());return Map.copyOf(m);}
    }
    void submit(Command command) throws IOException;
    /** Empty means unknown, never safe to resubmit. Backend must retain operation receipts. */
    Optional<Observation> observe(String environment, String operationId) throws IOException;
}
