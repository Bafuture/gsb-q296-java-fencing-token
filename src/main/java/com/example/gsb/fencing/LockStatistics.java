package com.example.gsb.fencing;

/**
 * 锁组件统计快照。
 *
 * @param acquisitions        成功加锁次数（含可重入加锁）
 * @param renewals            成功续约次数
 * @param rejectedStaleWrites 被资源侧拒绝的过期写入数
 * @param tokensIssued        已分配的 token 数量
 * @param firstToken          首个分配的 token，未分配时为 -1
 * @param lastToken           最近分配的 token，未分配时为 -1
 * @param tokenIncrementSpan  token 递增幅度（最近 token 与首个 token 的差值）
 */
public record LockStatistics(
        long acquisitions,
        long renewals,
        long rejectedStaleWrites,
        long tokensIssued,
        long firstToken,
        long lastToken,
        long tokenIncrementSpan) {
}
