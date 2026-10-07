package com.example.gsb.fencing;

/**
 * 锁组件相关异常的根类型。
 */
public class LockException extends RuntimeException {

    public LockException(String message) {
        super(message);
    }
}
