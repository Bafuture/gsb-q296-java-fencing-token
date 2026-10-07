package com.example.gsb.fencing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FencingLockTest {

    private static final long LEASE_MILLIS = 1_000L;

    private ManualClock clock;
    private LockStats stats;
    private FencingLock lock;
    private FencedResource resource;

    @BeforeEach
    void setUp() {
        clock = new ManualClock();
        stats = new LockStats();
        lock = new FencingLock("resource-lock", clock, stats);
        resource = new FencedResource("protected-resource", stats);
    }

    @Test
    void assignsStrictlyIncreasingTokensOnEachAcquisition() {
        LockHandle first = lock.tryLock("owner-a", LEASE_MILLIS).orElseThrow();
        lock.unlock(first);
        LockHandle second = lock.tryLock("owner-b", LEASE_MILLIS).orElseThrow();
        lock.unlock(second);
        LockHandle third = lock.tryLock("owner-c", LEASE_MILLIS).orElseThrow();

        assertThat(first.fencingToken()).isEqualTo(1L);
        assertThat(second.fencingToken()).isEqualTo(2L);
        assertThat(third.fencingToken()).isEqualTo(3L);
        assertThat(second.fencingToken()).isGreaterThan(first.fencingToken());
        assertThat(third.fencingToken()).isGreaterThan(second.fencingToken());
        assertThat(first.lockId()).isEqualTo("resource-lock");
    }

    @Test
    void rejectsWriteFromOldHolderWithStaleToken() {
        LockHandle oldHolder = lock.tryLock("owner-a", LEASE_MILLIS).orElseThrow();
        resource.write(oldHolder, "value-from-a");
        lock.unlock(oldHolder);

        LockHandle newHolder = lock.tryLock("owner-b", LEASE_MILLIS).orElseThrow();
        resource.write(newHolder, "value-from-b");

        assertThatThrownBy(() -> resource.write(oldHolder, "stale-overwrite"))
                .isInstanceOf(StaleWriteException.class)
                .hasMessageContaining("stale write rejected");
        assertThat(resource.read()).contains("value-from-b");
    }

    @Test
    void releasesLockAutomaticallyAfterLeaseExpiresAndIssuesLargerToken() {
        LockHandle expired = lock.tryLock("owner-a", LEASE_MILLIS).orElseThrow();

        assertThat(lock.tryLock("owner-b", LEASE_MILLIS)).isEmpty();

        clock.advanceMillis(LEASE_MILLIS + 1);

        assertThat(lock.isLocked()).isFalse();
        LockHandle newHolder = lock.tryLock("owner-b", LEASE_MILLIS).orElseThrow();
        assertThat(newHolder.fencingToken()).isGreaterThan(expired.fencingToken());
        assertThat(lock.unlock(expired)).isFalse();
    }

    @Test
    void renewExtendsLeaseWithoutChangingToken() {
        LockHandle holder = lock.tryLock("owner-a", LEASE_MILLIS).orElseThrow();

        clock.advanceMillis(LEASE_MILLIS - 100);
        assertThat(lock.renew(holder, LEASE_MILLIS)).isTrue();
        assertThat(holder.fencingToken()).isEqualTo(1L);

        clock.advanceMillis(LEASE_MILLIS - 100);
        assertThat(lock.isLocked()).isTrue();
        assertThat(lock.tryLock("owner-b", LEASE_MILLIS)).isEmpty();

        clock.advanceMillis(200);
        assertThat(lock.isLocked()).isFalse();
        assertThat(stats.renewalCount()).isEqualTo(1L);
    }

    @Test
    void renewFailsForStaleHandleAfterExpiry() {
        LockHandle expired = lock.tryLock("owner-a", LEASE_MILLIS).orElseThrow();
        clock.advanceMillis(LEASE_MILLIS + 1);
        lock.tryLock("owner-b", LEASE_MILLIS).orElseThrow();

        assertThat(lock.renew(expired, LEASE_MILLIS)).isFalse();
    }

    @Test
    void rejectsWriteFromCrashedHolderAfterRecovery() {
        LockHandle crashed = lock.tryLock("owner-a", LEASE_MILLIS).orElseThrow();
        resource.write(crashed, "before-crash");

        // owner-a crashes: no unlock, lease eventually expires.
        clock.advanceMillis(LEASE_MILLIS + 1);

        LockHandle successor = lock.tryLock("owner-b", LEASE_MILLIS).orElseThrow();
        resource.write(successor, "after-takeover");

        // owner-a recovers and still believes it holds the lock.
        assertThatThrownBy(() -> resource.write(crashed, "zombie-write"))
                .isInstanceOf(StaleWriteException.class)
                .satisfies(e -> {
                    StaleWriteException swe = (StaleWriteException) e;
                    assertThat(swe.presentedToken()).isEqualTo(crashed.fencingToken());
                    assertThat(swe.highestSeenToken()).isEqualTo(successor.fencingToken());
                });
        assertThat(resource.read()).contains("after-takeover");
        assertThat(stats.rejectedStaleWriteCount()).isEqualTo(1L);
    }

    @Test
    void reentrantAcquisitionReturnsSameTokenAndCountsHolds() {
        LockHandle first = lock.tryLock("owner-a", LEASE_MILLIS).orElseThrow();
        LockHandle second = lock.tryLock("owner-a", LEASE_MILLIS).orElseThrow();

        assertThat(second.fencingToken()).isEqualTo(first.fencingToken());
        assertThat(lock.reentrancyCount()).isEqualTo(2);
        assertThat(stats.lockAcquisitionCount()).isEqualTo(1L);

        assertThat(lock.unlock(first)).isTrue();
        assertThat(lock.isLocked()).isTrue();
        assertThat(lock.tryLock("owner-b", LEASE_MILLIS)).isEmpty();

        assertThat(lock.unlock(second)).isTrue();
        assertThat(lock.isLocked()).isFalse();

        LockHandle next = lock.tryLock("owner-b", LEASE_MILLIS).orElseThrow();
        assertThat(next.fencingToken()).isGreaterThan(first.fencingToken());
    }

    @Test
    void collectsStatistics() {
        LockHandle a = lock.tryLock("owner-a", LEASE_MILLIS).orElseThrow();
        lock.renew(a, LEASE_MILLIS);
        lock.renew(a, LEASE_MILLIS);
        resource.write(a, "v1");
        lock.unlock(a);

        LockHandle b = lock.tryLock("owner-b", LEASE_MILLIS).orElseThrow();
        resource.write(b, "v2");

        assertThatThrownBy(() -> resource.write(a, "stale"));
        assertThatThrownBy(() -> resource.write(a, "stale-again"));

        assertThat(stats.lockAcquisitionCount()).isEqualTo(2L);
        assertThat(stats.renewalCount()).isEqualTo(2L);
        assertThat(stats.rejectedStaleWriteCount()).isEqualTo(2L);
        assertThat(stats.tokenIncrements()).containsExactly(1L, 1L);
        assertThat(stats.totalTokenIncrement()).isEqualTo(2L);
    }

    @Test
    void equalTokenWriteIsAcceptedSoCurrentHolderCanRewrite() {
        LockHandle holder = lock.tryLock("owner-a", LEASE_MILLIS).orElseThrow();
        resource.write(holder, "v1");
        resource.write(holder, "v2");

        assertThat(resource.read()).contains("v2");
        assertThat(stats.rejectedStaleWriteCount()).isZero();
    }

    @Test
    void remainingLeaseReflectsClock() {
        lock.tryLock("owner-a", LEASE_MILLIS).orElseThrow();
        assertThat(lock.remainingLeaseMillis()).contains(LEASE_MILLIS);
        clock.advanceMillis(400);
        assertThat(lock.remainingLeaseMillis()).contains(LEASE_MILLIS - 400);
        clock.advanceMillis(LEASE_MILLIS);
        assertThat(lock.remainingLeaseMillis()).isEmpty();
        assertThat(lock.currentToken()).isEmpty();
    }

    @Test
    void rejectsInvalidLeaseArguments() {
        assertThatThrownBy(() -> lock.tryLock("owner-a", 0))
                .isInstanceOf(IllegalArgumentException.class);
        Optional<LockHandle> holder = lock.tryLock("owner-a", LEASE_MILLIS);
        assertThatThrownBy(() -> lock.renew(holder.orElseThrow(), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
