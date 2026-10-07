package com.example.gsb.fencing;

/**
 * 时间源抽象，测试可注入手动推进的时钟，避免依赖真实睡眠。
 */
@FunctionalInterface
public interface TimeSource {

    long millis();

    static TimeSource system() {
        return System::currentTimeMillis;
    }
}
