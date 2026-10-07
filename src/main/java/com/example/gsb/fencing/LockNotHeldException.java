package com.example.gsb.fencing;

/**
 * 句柄对应的锁未被当前持有者占有（已释放、已过期或不属于该持有者）。
 */
public class LockNotHeldException extends LockException {

    public LockNotHeldException(String message) {
        super(message);
    }
}
