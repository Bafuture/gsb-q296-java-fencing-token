package com.example.gsb.fencing;

import java.util.Objects;

/**
 * Proof of lock ownership returned to the client. Carries the fencing token
 * that must be presented to the protected resource on every write.
 */
public final class LockHandle {

    private final String lockId;
    private final String ownerId;
    private final long fencingToken;

    LockHandle(String lockId, String ownerId, long fencingToken) {
        this.lockId = Objects.requireNonNull(lockId, "lockId");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.fencingToken = fencingToken;
    }

    public String lockId() {
        return lockId;
    }

    public String ownerId() {
        return ownerId;
    }

    public long fencingToken() {
        return fencingToken;
    }

    @Override
    public String toString() {
        return "LockHandle{lockId=" + lockId + ", ownerId=" + ownerId + ", token=" + fencingToken + "}";
    }
}
