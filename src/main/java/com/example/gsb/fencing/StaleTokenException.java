package com.example.gsb.fencing;

/**
 * 写入携带的 fencing token 小于资源已见过的最大 token，写入被拒绝。
 */
public class StaleTokenException extends LockException {

    private final long writeToken;
    private final long maxSeenToken;

    public StaleTokenException(String resourceName, long writeToken, long maxSeenToken) {
        super("stale token rejected by resource '" + resourceName + "': write token "
                + writeToken + " < max seen token " + maxSeenToken);
        this.writeToken = writeToken;
        this.maxSeenToken = maxSeenToken;
    }

    public long writeToken() {
        return writeToken;
    }

    public long maxSeenToken() {
        return maxSeenToken;
    }
}
