package com.example.gsb.fencing;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 锁持有者客户端，封装“加锁 → 携带 token 写资源 → 续约/释放”的完整流程，
 * 并支持崩溃模拟：{@link #crash()} 后客户端停止一切操作（不再续约、不再释放），
 * 租约自然到期后被他人获取；{@link #recover()} 后客户端仍拿着旧 token，
 * 其写入会被资源侧拒绝。
 */
public class LockClient {

    private final String ownerId;
    private final FencingTokenLock lock;
    private final FencedResource resource;
    private boolean crashed;
    private LockHandle handle;

    public LockClient(String ownerId, FencingTokenLock lock, FencedResource resource) {
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.lock = Objects.requireNonNull(lock, "lock");
        this.resource = Objects.requireNonNull(resource, "resource");
    }

    public LockHandle acquire(String lockName, Duration lease) {
        ensureRunning();
        handle = lock.acquire(lockName, ownerId, lease);
        return handle;
    }

    public LockHandle renew(Duration extension) {
        ensureRunning();
        ensureHolding();
        handle = lock.renew(handle, extension);
        return handle;
    }

    /**
     * 使用当前句柄的 fencing token 写资源。
     */
    public void write(String value) {
        ensureRunning();
        ensureHolding();
        resource.write(handle.token(), value);
    }

    public void release() {
        ensureRunning();
        ensureHolding();
        lock.release(handle);
        handle = null;
    }

    /**
     * 模拟崩溃：客户端停止一切操作，租约将自然到期。
     */
    public void crash() {
        crashed = true;
    }

    /**
     * 模拟恢复：客户端重新上线，但仍持有崩溃前的旧 token。
     */
    public void recover() {
        crashed = false;
    }

    public boolean isCrashed() {
        return crashed;
    }

    public Optional<LockHandle> currentHandle() {
        return Optional.ofNullable(handle);
    }

    public String ownerId() {
        return ownerId;
    }

    private void ensureRunning() {
        if (crashed) {
            throw new IllegalStateException("client '" + ownerId + "' is crashed");
        }
    }

    private void ensureHolding() {
        if (handle == null) {
            throw new IllegalStateException("client '" + ownerId + "' holds no lock");
        }
    }
}
