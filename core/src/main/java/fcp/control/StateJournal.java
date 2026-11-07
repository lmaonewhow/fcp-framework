package fcp.control;
import java.io.IOException;
/** Durable control-plane CAS boundary; production hosts provide a database implementation. */
public interface StateJournal extends AutoCloseable {
    record Snapshot(long revision,String json) {}
    /** Empty store is revision zero with a null JSON payload. */
    Snapshot load() throws IOException;
    /** Persist the complete next state atomically, incrementing storage revision or failing on conflict. */
    Snapshot compareAndSet(long expectedRevision,String json) throws IOException;
    @Override void close() throws IOException;
}
