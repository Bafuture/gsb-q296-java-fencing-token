package com.example.gsb.fencing;

import java.util.Objects;

/**
 * 加锁成功后返回的句柄：锁标识（lockId）与 fencing token 一起返回。
 *
 * @param lockId          本次占有的唯一标识（每次新占有都不同，可重入时不变）
 * @param lockName        锁名称
 * @param ownerId         持有者标识
 * @param token           fencing token，严格递增
 * @param expiresAtMillis 租约到期时间（毫秒，取自锁服务的 TimeSource）
 */
public record LockHandle(String lockId, String lockName, String ownerId, long token, long expiresAtMillis) {

    public LockHandle {
        Objects.requireNonNull(lockId, "lockId");
        Objects.requireNonNull(lockName, "lockName");
        Objects.requireNonNull(ownerId, "ownerId");
    }
}
