package com.example.gsb.fencing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 资源侧 fencing：旧持有者（较小 token）无法覆盖新持有者（较大 token）的写入。
 */
class FencedResourceTest {

    private ManualTimeSource clock;
    private LockMetrics metrics;
    private FencingTokenLock lock;
    private FencedResource resource;

    @BeforeEach
    void setUp() {
        clock = new ManualTimeSource();
        metrics = new LockMetrics();
        lock = new FencingTokenLock(clock, metrics);
        resource = new FencedResource("account-1", metrics);
    }

    @Test
    void rejectsStaleWriteAfterLeaseExpiresAndNewOwnerWrites() {
        LockHandle alice = lock.acquire("account-1", "alice", Duration.ofSeconds(5));
        resource.write(alice.token(), "balance=100");

        // alice 没有续约，租约到期后锁自动释放
        clock.advance(Duration.ofSeconds(6));
        LockHandle bob = lock.acquire("account-1", "bob", Duration.ofSeconds(5));
        assertThat(bob.token()).isGreaterThan(alice.token());
        resource.write(bob.token(), "balance=200");

        // alice 仍然“以为”自己持有锁，继续用旧 token 写入 —— 必须被资源侧拒绝
        assertThatThrownBy(() -> resource.write(alice.token(), "balance=999"))
                .isInstanceOf(StaleTokenException.class)
                .satisfies(ex -> {
                    StaleTokenException stale = (StaleTokenException) ex;
                    assertThat(stale.writeToken()).isEqualTo(alice.token());
                    assertThat(stale.maxSeenToken()).isEqualTo(bob.token());
                });

        assertThat(resource.read()).isEqualTo("balance=200");
        assertThat(resource.maxSeenToken()).isEqualTo(bob.token());
        assertThat(metrics.snapshot().rejectedStaleWrites()).isEqualTo(1);
    }

    @Test
    void allowsRepeatedWritesWithSameToken() {
        LockHandle alice = lock.acquire("account-1", "alice", Duration.ofSeconds(5));
        resource.write(alice.token(), "a");
        resource.write(alice.token(), "b");

        assertThat(resource.read()).isEqualTo("b");
        assertThat(metrics.snapshot().rejectedStaleWrites()).isZero();
    }

    @Test
    void rejectsOutOfOrderTokensOnIndependentWrites() {
        resource.write(10L, "x");
        assertThatThrownBy(() -> resource.write(9L, "y"))
                .isInstanceOf(StaleTokenException.class);
        assertThat(resource.read()).isEqualTo("x");
        assertThat(metrics.snapshot().rejectedStaleWrites()).isEqualTo(1);
    }
}
