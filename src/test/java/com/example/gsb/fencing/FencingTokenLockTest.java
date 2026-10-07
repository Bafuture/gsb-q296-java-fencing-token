package com.example.gsb.fencing;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FencingTokenLockTest {

    private final ManualTimeSource clock = new ManualTimeSource();
    private final LockMetrics metrics = new LockMetrics();
    private final FencingTokenLock lock = new FencingTokenLock(clock, metrics);

    private static final Duration LEASE = Duration.ofSeconds(5);

    @Test
    void assignsStrictlyIncreasingTokensWithLockId() {
        LockHandle h1 = lock.acquire("resource-a", "alice", LEASE);
        LockHandle h2 = lock.acquire("resource-b", "bob", LEASE);

        assertThat(h1.token()).isPositive();
        assertThat(h2.token()).isGreaterThan(h1.token());
        assertThat(h1.lockId()).isNotBlank().isNotEqualTo(h2.lockId());

        lock.release(h1);
        LockHandle h3 = lock.acquire("resource-a", "carol", LEASE);
        assertThat(h3.token()).isGreaterThan(h2.token());
        assertThat(h3.lockId()).isNotEqualTo(h1.lockId());

        assertThat(lock.statistics().tokensIssued()).isEqualTo(3);
        assertThat(lock.statistics().firstToken()).isEqualTo(h1.token());
        assertThat(lock.statistics().lastToken()).isEqualTo(h3.token());
        assertThat(lock.statistics().tokenIncrementSpan()).isEqualTo(h3.token() - h1.token());
    }

    @Test
    void rejectsSecondOwnerWhileLeaseActive() {
        lock.acquire("res", "alice", LEASE);

        assertThat(lock.tryAcquire("res", "bob", LEASE)).isEmpty();
        assertThatThrownBy(() -> lock.acquire("res", "bob", LEASE))
                .isInstanceOf(LockUnavailableException.class);
        assertThat(lock.isHeld("res")).isTrue();
    }

    @Test
    void autoReleasesAfterLeaseExpiresAndNewOwnerGetsBiggerToken() {
        LockHandle old = lock.acquire("res", "alice", LEASE);
        assertThat(lock.isHeld("res")).isTrue();

        clock.advance(Duration.ofSeconds(4));
        assertThat(lock.isHeld("res")).as("still within lease").isTrue();

        clock.advance(Duration.ofSeconds(2));
        assertThat(lock.isHeld("res")).as("lease expired, lock auto released").isFalse();

        LockHandle fresh = lock.acquire("res", "bob", LEASE);
        assertThat(fresh.token()).isGreaterThan(old.token());
        assertThat(fresh.lockId()).isNotEqualTo(old.lockId());
    }

    @Test
    void renewExtendsLeaseAndKeepsTokenStable() {
        LockHandle original = lock.acquire("res", "alice", LEASE);

        clock.advance(Duration.ofSeconds(4));
        LockHandle renewed = lock.renew(original, LEASE);

        assertThat(renewed.token()).isEqualTo(original.token());
        assertThat(renewed.lockId()).isEqualTo(original.lockId());
        assertThat(renewed.expiresAtMillis()).isGreaterThan(original.expiresAtMillis());

        // 若续约无效，此刻（第 6 秒）锁早已过期
        clock.advance(Duration.ofSeconds(2));
        assertThat(lock.isHeld("res")).as("renewed lease keeps the lock").isTrue();
        assertThat(lock.statistics().renewals()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(4));
        assertThat(lock.isHeld("res")).as("renewed lease eventually expires").isFalse();
    }

    @Test
    void renewAfterExpiryIsRejected() {
        LockHandle handle = lock.acquire("res", "alice", LEASE);
        clock.advance(Duration.ofSeconds(6));

        assertThatThrownBy(() -> lock.renew(handle, LEASE))
                .isInstanceOf(LockExpiredException.class);
        assertThat(lock.statistics().renewals()).isZero();
    }

    @Test
    void renewWithStolenHandleIsRejected() {
        LockHandle alice = lock.acquire("res", "alice", LEASE);
        clock.advance(Duration.ofSeconds(6));
        LockHandle bob = lock.acquire("res", "bob", LEASE);
        assertThat(bob.token()).isGreaterThan(alice.token());

        // 旧持有者恢复后试图用旧句柄续约，必须失败
        assertThatThrownBy(() -> lock.renew(alice, LEASE))
                .isInstanceOf(LockNotHeldException.class);
    }

    @Test
    void reentrantAcquireReturnsSameTokenAndNeedsBalancedRelease() {
        LockHandle first = lock.acquire("res", "alice", LEASE);
        LockHandle second = lock.acquire("res", "alice", LEASE);
        LockHandle third = lock.acquire("res", "alice", LEASE);

        assertThat(second.token()).isEqualTo(first.token());
        assertThat(third.lockId()).isEqualTo(first.lockId());
        assertThat(lock.currentHoldCount("res")).isEqualTo(3);
        assertThat(lock.tryAcquire("res", "bob", LEASE)).isEmpty();

        lock.release(first);
        assertThat(lock.currentHoldCount("res")).as("reentrant lock still held").isEqualTo(2);
        lock.release(second);
        assertThat(lock.currentHoldCount("res")).isEqualTo(1);
        lock.release(third);
        assertThat(lock.isHeld("res")).isFalse();

        LockHandle next = lock.acquire("res", "bob", LEASE);
        assertThat(next.token()).isGreaterThan(first.token());
    }

    @Test
    void releasingAfterExpiryIsRejected() {
        LockHandle handle = lock.acquire("res", "alice", LEASE);
        clock.advance(Duration.ofSeconds(6));

        assertThatThrownBy(() -> lock.release(handle))
                .isInstanceOf(LockNotHeldException.class);
    }

    @Test
    void aggregatesLockStatistics() {
        LockHandle h1 = lock.acquire("res", "alice", LEASE);
        lock.acquire("res", "alice", LEASE); // 可重入，不分配新 token
        lock.renew(h1, LEASE);
        LockHandle h3 = lock.acquire("other", "bob", LEASE);

        LockStatistics stats = lock.statistics();
        assertThat(stats.acquisitions()).isEqualTo(3);
        assertThat(stats.renewals()).isEqualTo(1);
        assertThat(stats.tokensIssued()).isEqualTo(2);
        assertThat(stats.firstToken()).isEqualTo(h1.token());
        assertThat(stats.lastToken()).isEqualTo(h3.token());
        assertThat(stats.tokenIncrementSpan()).isEqualTo(h3.token() - h1.token());
    }
}
