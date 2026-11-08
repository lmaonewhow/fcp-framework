package fcp.release.routing;

import fcp.*;
import fcp.control.StateJournal;
import fcp.host.TrustedContext;
import java.io.IOException;
import java.time.*;
import java.util.*;

/** Host-only service. Its administration methods must be guarded by the host's release-management permission.
 * The supplied journal must be dedicated to this router, not the plugin lifecycle journal.
 */
public final class DurableReleaseRouter {
    public record Decision(String releaseId, String policyId, long revision, String reason, String affinity, Instant expiresAt) {}
    private final StateJournal journal;
    private final byte[] key;
    private final Clock clock;
    private final Duration ttl;
    private ReleasePolicy policy;
    private long revision;
    public DurableReleaseRouter(StateJournal journal, byte[] key, Clock clock, Duration ttl) throws IOException {
        this.journal=Objects.requireNonNull(journal); this.key=RoutingCrypto.key(key); this.clock=Objects.requireNonNull(clock);
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofMinutes(30))>0) throw new IllegalArgumentException("Affinity TTL must be at most 30 minutes");
        this.ttl=ttl; refresh();
    }
    private void refresh() throws IOException {
        StateJournal.Snapshot snapshot=journal.load(); revision=snapshot.revision();
        if(snapshot.json()==null){policy=null;return;}
        Map<String,Object> m=Schema.map(Json.parse(snapshot.json()));
        if(!m.keySet().equals(Set.of("format","id","stable","candidate","basisPoints","allow","exclude","enabled","epoch")) || Schema.number(m.get("format"))!=1) throw new IOException("Invalid release policy journal");
        policy=new ReleasePolicy(Schema.str(m.get("id")),Schema.str(m.get("stable")),Schema.str(m.get("candidate")),Math.toIntExact(Schema.number(m.get("basisPoints"))),strings(m.get("allow")),strings(m.get("exclude")),(Boolean)m.get("enabled"),Schema.number(m.get("epoch")));
    }
    private static Set<String> strings(Object value) { Set<String> result=new HashSet<>(); for(Object item:(List<?>)value)result.add(Schema.str(item));return result; }
    public synchronized long revision() throws IOException {refresh(); return revision;}
    public synchronized Optional<ReleasePolicy> policy() throws IOException {refresh(); return Optional.ofNullable(policy);}
    public synchronized long put(ReleasePolicy next,long expectedRevision) throws IOException {
        refresh();
        if(expectedRevision!=revision)throw new FcpException("RELEASE_REVISION_CONFLICT","Release policy changed");
        if(policy!=null && (!policy.id().equals(next.id()) || next.epoch()<policy.epoch()))throw new FcpException("INVALID_POLICY_CHANGE","Policy identity is fixed and epoch cannot decrease");
        Map<String,Object> m=new LinkedHashMap<>();m.put("format",1);m.put("id",next.id());m.put("stable",next.stableRelease());m.put("candidate",next.candidateRelease());m.put("basisPoints",next.basisPoints());m.put("allow",new TreeSet<>(next.allowTenants()));m.put("exclude",new TreeSet<>(next.excludeTenants()));m.put("enabled",next.enabled());m.put("epoch",next.epoch());
        revision=journal.compareAndSet(expectedRevision,Json.write(m)).revision();policy=next;return revision;
    }
    private void admitted(TrustedContext context) {
        Objects.requireNonNull(context);
        if(!clock.instant().isBefore(context.deadline()))throw new FcpException("CONTEXT_EXPIRED","Trusted context expired");
        if(policy==null || !policy.enabled())throw new FcpException("RELEASE_DISABLED","Release routing is disabled");
    }
    public synchronized int bucket(String tenantId) throws IOException {
        refresh(); if(policy==null)throw new FcpException("RELEASE_DISABLED","Release routing is not configured");
        byte[] mac=RoutingCrypto.mac(key,Json.write(List.of("bucket-v1",policy.id(),ReleasePolicy.valid(tenantId))));
        return (int)(Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(mac).getInt())%10000);
    }
    public synchronized Decision decide(TrustedContext context) throws IOException {
        refresh();admitted(context);String release=policy.stableRelease(),reason="STABLE";
        if(policy.excludeTenants().contains(context.tenantId()))reason="EXCLUDED";
        else if(policy.allowTenants().contains(context.tenantId())){release=policy.candidateRelease();reason="ALLOWLIST";}
        else if(bucket(context.tenantId())<policy.basisPoints()){release=policy.candidateRelease();reason="BUCKET";}
        Instant expiry=clock.instant().plus(ttl);if(context.deadline().isBefore(expiry))expiry=context.deadline();
        Map<String,Object> m=new LinkedHashMap<>();m.put("domain","affinity-v1");m.put("policy",policy.id());m.put("stable",policy.stableRelease());m.put("candidate",policy.candidateRelease());m.put("release",release);m.put("epoch",policy.epoch());m.put("tenant",context.tenantId());m.put("principal",context.principal().id());m.put("kind",context.principal().kind().name());m.put("authorization",context.authorizationRevision());m.put("expiry",expiry.toEpochMilli());
        return new Decision(release,policy.id(),revision,reason,RoutingCrypto.sign(key,Json.write(m)),expiry);
    }
    /** Extend a still-valid identity binding without re-bucketing or reviving expired/revoked affinity. */
    public synchronized Decision renew(TrustedContext context,String affinity) throws IOException {
        Decision previous=resume(context,affinity);
        Map<String,Object> value=Schema.map(Json.parse(RoutingCrypto.verify(key,affinity)));
        Instant expiry=clock.instant().plus(ttl);if(context.deadline().isBefore(expiry))expiry=context.deadline();
        value.put("expiry",expiry.toEpochMilli());
        return new Decision(previous.releaseId(),previous.policyId(),previous.revision(),"RENEWED_AFFINITY",RoutingCrypto.sign(key,Json.write(value)),expiry);
    }
    public synchronized Decision resume(TrustedContext context,String affinity) throws IOException {
        refresh();admitted(context);Map<String,Object> m=Schema.map(Json.parse(RoutingCrypto.verify(key,affinity)));
        String release=Schema.str(m.get("release"));Instant expiry=Instant.ofEpochMilli(Schema.number(m.get("expiry")));
        boolean ok="affinity-v1".equals(m.get("domain")) && policy.id().equals(m.get("policy")) && policy.stableRelease().equals(m.get("stable")) && policy.candidateRelease().equals(m.get("candidate")) && policy.epoch()==Schema.number(m.get("epoch")) && context.tenantId().equals(m.get("tenant")) && context.principal().id().equals(m.get("principal")) && context.principal().kind().name().equals(m.get("kind")) && context.authorizationRevision()==Schema.number(m.get("authorization")) && clock.instant().isBefore(expiry) && (release.equals(policy.stableRelease()) || release.equals(policy.candidateRelease()));
        if(policy.excludeTenants().contains(context.tenantId()) && !release.equals(policy.stableRelease()))ok=false;
        if(!ok)throw new FcpException("STALE_RELEASE_AFFINITY","Release context expired or was revoked; resolve before a new operation");
        return new Decision(release,policy.id(),revision,"AFFINITY",affinity,expiry);
    }
}
