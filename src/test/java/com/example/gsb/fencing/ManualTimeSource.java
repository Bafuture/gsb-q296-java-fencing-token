package com.example.gsb.fencing;

import java.time.Duration;

/**
 * 测试用时间源：时间不随真实时钟流动，只能通过 {@link #advance(Duration)} 手动推进。
 */
public class ManualTimeSource implements TimeSource {

    private long now;

    public ManualTimeSource() {
        this(0L);
    }

    public ManualTimeSource(long startMillis) {
        this.now = startMillis;
    }

    @Override
    public long millis() {
        return now;
    }

    public void advance(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("cannot move time backwards");
        }
        now += duration.toMillis();
    }
}
