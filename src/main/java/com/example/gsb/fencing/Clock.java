package com.example.gsb.fencing;

/** Source of time, abstracted so tests can advance time deterministically. */
public interface Clock {

    long nowMillis();

    /** Clock backed by the system time. */
    static Clock system() {
        return System::currentTimeMillis;
    }
}
