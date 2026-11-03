package fcp.state;

/** Authoritative desired state, independent of process/JVM instance generations.
 * Implementations must make compareAndSet atomic and must never default on I/O failure.
 */
public interface PluginStateStore {
    record Key(String scope, String plugin) {
        public Key {
            if (scope == null || !scope.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}") ||
                plugin == null || !plugin.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))
                throw new IllegalArgumentException("Invalid state key");
        }
    }
    record State(boolean enabled, long revision) {
        public State { if (revision < 1) throw new IllegalArgumentException("Invalid state revision"); }
    }
    State getOrCreate(Key key, boolean initialEnabled);
    State compareAndSet(Key key, long expectedRevision, boolean enabled, String actor);
}
