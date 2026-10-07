package com.example.gsb.fencing;

/**
 * 租约已过期，续约不再被接受。
 */
public class LockExpiredException extends LockException {

    public LockExpiredException(String message) {
        super(message);
    }
}
