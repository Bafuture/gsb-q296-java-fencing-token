package com.example.gsb.fencing;

/** Thrown by a fenced resource when a write carries a token older than the highest token seen. */
public final class StaleWriteException extends RuntimeException {

    private final long presentedToken;
    private final long highestSeenToken;

    public StaleWriteException(long presentedToken, long highestSeenToken) {
        super("stale write rejected: token " + presentedToken
                + " is older than the highest token seen " + highestSeenToken);
        this.presentedToken = presentedToken;
        this.highestSeenToken = highestSeenToken;
    }

    public long presentedToken() {
        return presentedToken;
    }

    public long highestSeenToken() {
        return highestSeenToken;
    }
}
