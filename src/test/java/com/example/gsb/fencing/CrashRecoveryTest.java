package com.example.gsb.fencing;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 持有者崩溃模拟：崩溃后不再续约，租约到期被他人获取；旧持有者恢复后的写入
 * 必须被资源侧拒绝，新持有者的数据不受影响。
 */
class CrashRecoveryTest {

    @Test
    void crashedOwnerLosesLeaseAndRecoveredWritesAreRejected() {
        ManualTimeSource clock = new ManualTimeSource();
        LockMetrics metrics = new LockMetrics();
        FencingTokenLock lock = new FencingTokenLock(clock, metrics);
        FencedResource resource = new FencedResource("account-1", metrics);

        LockClient alice = new LockClient("alice", lock, resource);
        LockClient bob = new LockClient("bob", lock, resource);

        alice.acquire("account-1", Duration.ofSeconds(5));
        alice.write("by-alice-1");

        // alice 崩溃：无法续约、无法释放
        alice.crash();
        assertThat(alice.isCrashed()).isTrue();
        assertThatThrownBy(() -> alice.write("should-not-happen"))
                .isInstanceOf(IllegalStateException.class);

        clock.advance(Duration.ofSeconds(6));
        assertThat(lock.isHeld("account-1")).isFalse();

        // 租约到期，bob 获取到更大的 token 并写入
        LockHandle bobHandle = bob.acquire("account-1", Duration.ofSeconds(5));
        long aliceToken = alice.currentHandle().orElseThrow().token();
        assertThat(bobHandle.token()).isGreaterThan(aliceToken);
        bob.write("by-bob-1");

        // alice 恢复，拿着旧 token 试图写入与续约，都必须失败
        alice.recover();
        assertThatThrownBy(() -> alice.write("by-alice-2"))
                .isInstanceOf(StaleTokenException.class);
        assertThat(alice.currentHandle()).hasValueSatisfying(
                h -> assertThat(h.token()).isEqualTo(aliceToken));

        // 资源保持 bob 的写入，统计体现一次过期写入拒绝
        assertThat(resource.read()).isEqualTo("by-bob-1");
        assertThat(metrics.snapshot().rejectedStaleWrites()).isEqualTo(1);
    }

    @Test
    void crashedOwnerReleasingAfterReacquireIsRejected() {
        ManualTimeSource clock = new ManualTimeSource();
        FencingTokenLock lock = new FencingTokenLock(clock);
        FencedResource resource = new FencedResource("account-1");
        LockClient alice = new LockClient("alice", lock, resource);
        LockClient bob = new LockClient("bob", lock, resource);

        alice.acquire("account-1", Duration.ofSeconds(5));
        alice.crash();
        clock.advance(Duration.ofSeconds(6));
        bob.acquire("account-1", Duration.ofSeconds(5));
        alice.recover();

        // 旧句柄已不属于当前占有，释放同样被拒绝，且不会误释放 bob 的锁
        assertThatThrownBy(alice::release).isInstanceOf(LockNotHeldException.class);
        assertThat(lock.isHeld("account-1")).isTrue();
    }
}
