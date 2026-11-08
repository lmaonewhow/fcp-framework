package fcp.release.observation;

/** Deployment-owned hook; token captures generation at request admission, not completion. */
public interface RequestObserver {
    enum BusinessOutcome { SUCCESS, FAILURE, UNKNOWN }
    interface Ticket { void complete(boolean technicalFailure,BusinessOutcome business,boolean unknownResult,long nanos); }
    default void securityViolationAtRevision(String releaseId,long routeRevision){throw new IllegalStateException("SECURITY_OBSERVER_UNAVAILABLE");}
    Ticket begin(String releaseId,long routeRevision);
    RequestObserver NONE=(release,revision)->(technical,business,unknown,nanos)->{};
}
