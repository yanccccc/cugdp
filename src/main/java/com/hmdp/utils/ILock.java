package com.hmdp.utils;

/**
 * Author:CodeCan
 * Time:2024/8/22
 */
public interface ILock {
    /**
     * 尝试获取锁
     * @param timeoutSec 锁的过期时间
     * @return 获取锁成功返回true，失败返回false
     */
    boolean tryLock(Long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
