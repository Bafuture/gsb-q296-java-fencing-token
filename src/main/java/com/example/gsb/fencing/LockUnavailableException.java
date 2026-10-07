package com.example.gsb.fencing;

/**
 * 锁正被其他持有者占有时加锁失败。
 */
public class LockUnavailableException extends LockException {

    public LockUnavailableException(String message) {
        super(message);
    }
}
