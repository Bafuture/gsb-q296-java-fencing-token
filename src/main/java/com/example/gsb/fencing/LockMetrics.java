package com.example.gsb.fencing;

/**
 * 锁与资源共享的可变指标收集器。锁服务记录加锁/续约/token 分配，
 * 资源记录被拒绝的过期写入，最终汇总为一份 {@link LockStatistics}。
 */
public class LockMetrics {

    private long acquisitions;
    private long renewals;
    private long rejectedStaleWrites;
    private long tokensIssued;
    private long firstToken = -1;
    private long lastToken = -1;

    public synchronized void recordAcquisition() {
        acquisitions++;
    }

    public synchronized void recordRenewal() {
        renewals++;
    }

    public synchronized void recordRejectedStaleWrite() {
        rejectedStaleWrites++;
    }

    public synchronized void recordTokenIssued(long token) {
        if (tokensIssued == 0) {
            firstToken = token;
        }
        lastToken = token;
        tokensIssued++;
    }

    public synchronized LockStatistics snapshot() {
        long span = tokensIssued == 0 ? 0 : lastToken - firstToken;
        return new LockStatistics(acquisitions, renewals, rejectedStaleWrites,
                tokensIssued, firstToken, lastToken, span);
    }
}
