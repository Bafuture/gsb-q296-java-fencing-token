package com.example.gsb.fencing;

/** A clock controlled by the caller, useful for simulating lease expiry and crashes. */
public final class ManualClock implements Clock {

    private long nowMillis;

    public ManualClock() {
        this(0L);
    }

    public ManualClock(long startMillis) {
        this.nowMillis = startMillis;
    }

    @Override
    public long nowMillis() {
        return nowMillis;
    }

    public void advanceMillis(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("millis must be >= 0");
        }
        nowMillis += millis;
    }
}
