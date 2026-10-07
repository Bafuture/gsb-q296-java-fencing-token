package com.example.gsb.fencing;

import java.util.Objects;
import java.util.Optional;

/**
 * A distributed lock with fencing tokens, backed by an in-memory state object.
 *
 * <p>Every successful (non-reentrant) acquisition is assigned a strictly
 * increasing fencing token. The lock supports leases with automatic expiry,
 * renewal by the current holder, and reentrant acquisition by the same owner.
 * Expiry is evaluated lazily against the injected {@link Clock}, so a crashed
 * holder's lease is released as soon as it expires without any background
 * threads.
 */
public final class FencingLock {

    private final String lockId;
    private final Clock clock;
    private final LockStats stats;

    private String ownerId;
    private long fencingToken;
    private long expiresAtMillis;
    private int reentrancyCount;

    public FencingLock(String lockId, Clock clock, LockStats stats) {
        this.lockId = Objects.requireNonNull(lockId, "lockId");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stats = Objects.requireNonNull(stats, "stats");
    }

    /**
     * Attempts to acquire the lock for {@code ownerId} with the given lease.
     *
     * <p>If the same owner already holds the lock, the acquisition is reentrant:
     * the same token is returned and the hold count increases. If a previous
     * holder's lease has expired, the lock is released automatically and the new
     * owner receives a strictly larger token.
     *
     * @return the handle carrying the fencing token, or empty if another owner
     *         holds an unexpired lease
     */
    public synchronized Optional<LockHandle> tryLock(String ownerId, long leaseMillis) {
        Objects.requireNonNull(ownerId, "ownerId");
        if (leaseMillis <= 0) {
            throw new IllegalArgumentException("leaseMillis must be > 0");
        }
        releaseIfExpired();
        if (isHeld()) {
            if (!this.ownerId.equals(ownerId)) {
                return Optional.empty();
            }
            reentrancyCount++;
            return Optional.of(handle());
        }
        this.ownerId = ownerId;
        this.fencingToken++;
        this.reentrancyCount = 1;
        this.expiresAtMillis = clock.nowMillis() + leaseMillis;
        stats.recordAcquisition(fencingToken);
        return Optional.of(handle());
    }

    /**
     * Extends the lease of the current holder. The fencing token is unchanged.
     *
     * @return true if the lease was extended; false if the handle is stale or
     *         the lease has already expired
     */
    public synchronized boolean renew(LockHandle handle, long extensionMillis) {
        Objects.requireNonNull(handle, "handle");
        if (extensionMillis <= 0) {
            throw new IllegalArgumentException("extensionMillis must be > 0");
        }
        releaseIfExpired();
        if (!isValidHandle(handle)) {
            return false;
        }
        expiresAtMillis = clock.nowMillis() + extensionMillis;
        stats.recordRenewal();
        return true;
    }

    /**
     * Releases one level of the hold. The lock is fully released when the
     * reentrancy count reaches zero.
     *
     * @return true if the handle was valid and a hold level was released
     */
    public synchronized boolean unlock(LockHandle handle) {
        Objects.requireNonNull(handle, "handle");
        releaseIfExpired();
        if (!isValidHandle(handle)) {
            return false;
        }
        if (--reentrancyCount == 0) {
            release();
        }
        return true;
    }

    /** True if the lock is currently held and the lease has not expired. */
    public synchronized boolean isLocked() {
        releaseIfExpired();
        return isHeld();
    }

    /** The token of the current holder, or empty if the lock is free. */
    public synchronized Optional<Long> currentToken() {
        releaseIfExpired();
        return isHeld() ? Optional.of(fencingToken) : Optional.empty();
    }

    /** Millis remaining on the current lease, or empty if the lock is free. */
    public synchronized Optional<Long> remainingLeaseMillis() {
        releaseIfExpired();
        return isHeld() ? Optional.of(expiresAtMillis - clock.nowMillis()) : Optional.empty();
    }

    /** Current reentrancy depth, or 0 if the lock is free. */
    public synchronized int reentrancyCount() {
        releaseIfExpired();
        return isHeld() ? reentrancyCount : 0;
    }

    public String lockId() {
        return lockId;
    }

    public LockStats stats() {
        return stats;
    }

    private boolean isHeld() {
        return ownerId != null;
    }

    private boolean isValidHandle(LockHandle handle) {
        return isHeld()
                && handle.lockId().equals(lockId)
                && handle.ownerId().equals(ownerId)
                && handle.fencingToken() == fencingToken;
    }

    private LockHandle handle() {
        return new LockHandle(lockId, ownerId, fencingToken);
    }

    private void releaseIfExpired() {
        if (isHeld() && clock.nowMillis() >= expiresAtMillis) {
            release();
        }
    }

    private void release() {
        ownerId = null;
        reentrancyCount = 0;
        expiresAtMillis = 0L;
    }
}
