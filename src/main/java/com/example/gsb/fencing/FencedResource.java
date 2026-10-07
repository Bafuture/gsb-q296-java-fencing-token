package com.example.gsb.fencing;

import java.util.Objects;
import java.util.Optional;

/**
 * A resource protected by fencing tokens. It remembers the highest token it has
 * ever seen and rejects any write carrying a smaller token, so a stale lock
 * holder can never overwrite a newer holder's data.
 */
public final class FencedResource {

    private final String name;
    private final LockStats stats;
    private long highestSeenToken;
    private String value;

    public FencedResource(String name, LockStats stats) {
        this.name = Objects.requireNonNull(name, "name");
        this.stats = Objects.requireNonNull(stats, "stats");
    }

    /**
     * Writes {@code newValue} if {@code token} is at least as high as every token
     * seen before; otherwise the write is rejected with a {@link StaleWriteException}.
     */
    public synchronized void write(long token, String newValue) {
        if (token < highestSeenToken) {
            stats.recordRejectedStaleWrite();
            throw new StaleWriteException(token, highestSeenToken);
        }
        highestSeenToken = token;
        value = newValue;
    }

    /** Convenience overload taking the handle issued by the lock. */
    public void write(LockHandle handle, String newValue) {
        write(handle.fencingToken(), newValue);
    }

    public synchronized Optional<String> read() {
        return Optional.ofNullable(value);
    }

    public synchronized long highestSeenToken() {
        return highestSeenToken;
    }

    public String name() {
        return name;
    }
}
