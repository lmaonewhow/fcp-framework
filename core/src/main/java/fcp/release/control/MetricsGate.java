package fcp.release.control;

import java.time.*;

/** Fail-closed automation decision. Does not itself change routes. */
public final class MetricsGate {
    public enum Decision { PROCEED, PAUSE, ABORT }
    public record Sample(Instant observedAt, String releaseId, long generation, long requests,
                         long technicalErrors, long businessErrors, long unknownResults,
                         double p95Millis, boolean securityViolation) {}
    public record Limits(long minimumSamples, Duration maximumAge, double maximumErrorRate, double maximumP95Millis) {
        public Limits { if(minimumSamples<1 || maximumAge.isNegative() || maximumAge.isZero() || !Double.isFinite(maximumErrorRate) || maximumErrorRate<0 || maximumErrorRate>1 || !Double.isFinite(maximumP95Millis) || maximumP95Millis<=0)throw new IllegalArgumentException("Invalid metric limits"); }
    }
    private MetricsGate(){}
    public static Decision evaluate(Sample s, Limits l, Instant now, String releaseId, long generation) {
        if(s!=null && releaseId.equals(s.releaseId()) && generation==s.generation() && s.securityViolation())return Decision.ABORT;
        if(s==null || s.observedAt()==null || !releaseId.equals(s.releaseId()) || generation!=s.generation() || s.observedAt().isAfter(now) || s.observedAt().plus(l.maximumAge()).isBefore(now))return Decision.PAUSE;
        if(s.securityViolation())return Decision.ABORT;
        if(s.requests()<l.minimumSamples() || s.technicalErrors()<0 || s.businessErrors()<0 || s.unknownResults()<0 || s.technicalErrors()>s.requests() || s.businessErrors()>s.requests() || s.unknownResults()>s.requests() || !Double.isFinite(s.p95Millis()) || s.p95Millis()<0)return Decision.PAUSE;
        if(s.unknownResults()>0)return Decision.PAUSE;
        if((double)s.technicalErrors()/s.requests()>l.maximumErrorRate() || (double)s.businessErrors()/s.requests()>l.maximumErrorRate() || s.p95Millis()>l.maximumP95Millis())return Decision.ABORT;
        return Decision.PROCEED;
    }
}
