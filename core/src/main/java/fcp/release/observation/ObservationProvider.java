package fcp.release.observation;

import fcp.release.control.MetricsGate;
import java.io.IOException;
import java.time.Instant;

/** Host-configured, trusted metrics source. Never deserialize a browser-supplied window. */
public interface ObservationProvider {
    record Window(String sourceEpoch,long sequence,Instant openedAt,Instant closedAt,
                  boolean complete,String reason,MetricsGate.Sample sample) {}
    /** Freeze one window. The same samples must never be counted again in a later window. */
    default boolean confirmedSecurityViolation(String releaseId,long generation) throws IOException {return false;}
    Window closeWindow(String releaseId,long generation) throws IOException;
}
