package fcp.state;

import fcp.FcpException;
import java.util.HashMap;
import java.util.Map;

/** Explicit volatile implementation for tests and standalone non-persistent embeddings. */
public final class MemoryPluginStateStore implements PluginStateStore {
    private final Map<Key, State> states = new HashMap<>();
    private final int capacity;
    public MemoryPluginStateStore() { this(128); }
    public MemoryPluginStateStore(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }
    public synchronized State getOrCreate(Key key, boolean initialEnabled) {
        State state = states.get(key);
        if (state == null) {
            if (states.size() >= capacity) throw new FcpException("RESOURCE_LIMIT", "Plugin state capacity reached");
            state = new State(initialEnabled, 1); states.put(key, state);
        }
        return state;
    }
    public synchronized State compareAndSet(Key key, long expectedRevision, boolean enabled, String actor) {
        if (actor == null || actor.isBlank() || actor.length() > 100) throw new IllegalArgumentException("actor");
        State old = states.get(key);
        if (old == null || old.revision() != expectedRevision) throw new FcpException("STALE_COMPOSITION", "Persisted plugin revision changed");
        State next = new State(enabled, Math.addExact(old.revision(), 1));
        states.put(key, next); return next;
    }
}
