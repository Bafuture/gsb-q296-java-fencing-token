package com.example.gsb.fencing;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 带 fencing token 的分布式锁（内存模拟实现）。
 *
 * <p>语义：
 * <ul>
 *   <li>每次新的成功加锁分配一个严格递增的 token，随 {@link LockHandle} 返回；</li>
 *   <li>租约到期后锁自动释放（惰性回收：在任意操作时检查），他人随后可获取更大的 token；</li>
 *   <li>持有者在到期前可续约，续约不改变 token；</li>
 *   <li>同一持有者对同一把锁重复加锁是可重入的：返回同一 token，重入计数加一，
 *       需释放相同次数后锁才真正释放；</li>
 *   <li>锁本身不阻止旧持有者写入——fencing token 的校验发生在资源侧
 *       （见 {@link FencedResource}），过期持有者的写入会被拒绝。</li>
 * </ul>
 */
public class FencingTokenLock {

    private final TimeSource timeSource;
    private final LockMetrics metrics;
    private final Map<String, LockEntry> locks = new HashMap<>();
    private long nextToken = 1;

    public FencingTokenLock() {
        this(TimeSource.system(), new LockMetrics());
    }

    public FencingTokenLock(TimeSource timeSource) {
        this(timeSource, new LockMetrics());
    }

    public FencingTokenLock(TimeSource timeSource, LockMetrics metrics) {
        this.timeSource = Objects.requireNonNull(timeSource, "timeSource");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * 加锁；锁被其他持有者占用且未过期时抛出 {@link LockUnavailableException}。
     */
    public LockHandle acquire(String lockName, String ownerId, Duration lease) {
        return tryAcquire(lockName, ownerId, lease)
                .orElseThrow(() -> new LockUnavailableException(
                        "lock '" + lockName + "' is held by another owner"));
    }

    /**
     * 尝试加锁，失败时返回 {@link Optional#empty()} 而不是抛异常。
     */
    public synchronized Optional<LockHandle> tryAcquire(String lockName, String ownerId, Duration lease) {
        Objects.requireNonNull(lockName, "lockName");
        Objects.requireNonNull(ownerId, "ownerId");
        requirePositive(lease);

        long now = timeSource.millis();
        LockEntry entry = locks.get(lockName);
        if (entry != null && entry.isExpired(now)) {
            // 租约过期，自动释放（模拟持有者崩溃后锁被回收）
            locks.remove(lockName);
            entry = null;
        }

        if (entry != null) {
            if (!entry.ownerId.equals(ownerId)) {
                return Optional.empty();
            }
            // 可重入：同一持有者，token 与锁标识不变，重入计数加一，租约顺延
            entry.holdCount++;
            entry.expiresAtMillis = now + lease.toMillis();
            metrics.recordAcquisition();
            return Optional.of(entry.toHandle());
        }

        long token = nextToken++;
        LockEntry created = new LockEntry(UUID.randomUUID().toString(), lockName, ownerId,
                token, 1, now + lease.toMillis());
        locks.put(lockName, created);
        metrics.recordAcquisition();
        metrics.recordTokenIssued(token);
        return Optional.of(created.toHandle());
    }

    /**
     * 续约：持有者延长租约，token 保持不变。
     *
     * @throws LockNotHeldException 句柄不对应当前占有（已释放或已被他人获取）
     * @throws LockExpiredException 租约已过期，无法再续约
     */
    public synchronized LockHandle renew(LockHandle handle, Duration extension) {
        Objects.requireNonNull(handle, "handle");
        requirePositive(extension);

        long now = timeSource.millis();
        LockEntry entry = locks.get(handle.lockName());
        if (entry == null || !entry.lockId.equals(handle.lockId())) {
            throw new LockNotHeldException(
                    "lock '" + handle.lockName() + "' is not held by this handle");
        }
        if (entry.isExpired(now)) {
            locks.remove(handle.lockName());
            throw new LockExpiredException(
                    "lock '" + handle.lockName() + "' lease has expired, renew rejected");
        }
        entry.expiresAtMillis = now + extension.toMillis();
        metrics.recordRenewal();
        return entry.toHandle();
    }

    /**
     * 释放一次占有。可重入锁需释放与加锁相同的次数才真正释放。
     *
     * @throws LockNotHeldException 句柄不对应当前占有，或租约已过期（视为已自动释放）
     */
    public synchronized void release(LockHandle handle) {
        Objects.requireNonNull(handle, "handle");
        long now = timeSource.millis();
        LockEntry entry = locks.get(handle.lockName());
        if (entry == null || !entry.lockId.equals(handle.lockId())) {
            throw new LockNotHeldException(
                    "lock '" + handle.lockName() + "' is not held by this handle");
        }
        if (entry.isExpired(now)) {
            locks.remove(handle.lockName());
            throw new LockNotHeldException(
                    "lock '" + handle.lockName() + "' lease already expired");
        }
        if (--entry.holdCount == 0) {
            locks.remove(handle.lockName());
        }
    }

    /**
     * 锁当前是否被占有（未过期的占有才算）。
     */
    public synchronized boolean isHeld(String lockName) {
        LockEntry entry = locks.get(lockName);
        if (entry == null) {
            return false;
        }
        if (entry.isExpired(timeSource.millis())) {
            locks.remove(lockName);
            return false;
        }
        return true;
    }

    /**
     * 当前重入计数，未被占有时返回 0。
     */
    public synchronized int currentHoldCount(String lockName) {
        return isHeld(lockName) ? locks.get(lockName).holdCount : 0;
    }

    /**
     * 组件统计快照（加锁次数、续约次数、被拒绝的过期写入数、token 递增幅度等）。
     */
    public LockStatistics statistics() {
        return metrics.snapshot();
    }

    private static void requirePositive(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive: " + duration);
        }
    }

    private static final class LockEntry {
        private final String lockId;
        private final String lockName;
        private final String ownerId;
        private final long token;
        private int holdCount;
        private long expiresAtMillis;

        private LockEntry(String lockId, String lockName, String ownerId,
                          long token, int holdCount, long expiresAtMillis) {
            this.lockId = lockId;
            this.lockName = lockName;
            this.ownerId = ownerId;
            this.token = token;
            this.holdCount = holdCount;
            this.expiresAtMillis = expiresAtMillis;
        }

        private boolean isExpired(long now) {
            return now >= expiresAtMillis;
        }

        private LockHandle toHandle() {
            return new LockHandle(lockId, lockName, ownerId, token, expiresAtMillis);
        }
    }
}
