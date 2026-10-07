package com.example.gsb.fencing;

import java.util.Objects;

/**
 * 被锁保护的资源（内存模拟）。写入时必须携带 fencing token：
 * 小于资源已见过的最大 token 的写入会被拒绝并抛出 {@link StaleTokenException}，
 * 因此租约过期后的旧持有者无法覆盖新持有者的写入。
 */
public class FencedResource {

    private final String name;
    private final LockMetrics metrics;
    private long maxSeenToken = Long.MIN_VALUE;
    private String value;

    public FencedResource(String name) {
        this(name, new LockMetrics());
    }

    public FencedResource(String name, LockMetrics metrics) {
        this.name = Objects.requireNonNull(name, "name");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * 携带 fencing token 写入。
     *
     * @throws StaleTokenException token 小于资源已见过的最大 token
     */
    public synchronized void write(long token, String newValue) {
        if (token < maxSeenToken) {
            metrics.recordRejectedStaleWrite();
            throw new StaleTokenException(name, token, maxSeenToken);
        }
        maxSeenToken = token;
        this.value = newValue;
    }

    public synchronized String read() {
        return value;
    }

    public synchronized long maxSeenToken() {
        return maxSeenToken;
    }

    public String name() {
        return name;
    }
}
