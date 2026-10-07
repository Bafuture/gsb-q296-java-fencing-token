package com.example.gsb.fencing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Mutable statistics collector shared by the lock and the fenced resource. */
public final class LockStats {

    private long lockAcquisitionCount;
    private long rejectedStaleWriteCount;
    private long renewalCount;
    private long lastIssuedToken;
    private long totalTokenIncrement;
    private final List<Long> tokenIncrements = new ArrayList<>();

    synchronized void recordAcquisition(long newToken) {
        lockAcquisitionCount++;
        long increment = lastIssuedToken == 0L ? newToken : newToken - lastIssuedToken;
        lastIssuedToken = newToken;
        totalTokenIncrement += increment;
        tokenIncrements.add(increment);
    }

    synchronized void recordRenewal() {
        renewalCount++;
    }

    synchronized void recordRejectedStaleWrite() {
        rejectedStaleWriteCount++;
    }

    /** Number of successful (non-reentrant) lock acquisitions. */
    public synchronized long lockAcquisitionCount() {
        return lockAcquisitionCount;
    }

    /** Number of writes rejected by the resource because their token was stale. */
    public synchronized long rejectedStaleWriteCount() {
        return rejectedStaleWriteCount;
    }

    /** Number of successful lease renewals. */
    public synchronized long renewalCount() {
        return renewalCount;
    }

    /** Sum of the deltas between consecutively issued fencing tokens. */
    public synchronized long totalTokenIncrement() {
        return totalTokenIncrement;
    }

    /** The delta of each issued token relative to the previously issued token. */
    public synchronized List<Long> tokenIncrements() {
        return Collections.unmodifiableList(new ArrayList<>(tokenIncrements));
    }
}
