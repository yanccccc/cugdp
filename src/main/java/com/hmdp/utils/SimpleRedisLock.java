package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;


import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Author:CodeCan
 * Time:2024/8/22
 */
public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    private String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    public static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(Long timeoutSec) {
        // 获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 设置锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name,
                threadId, timeoutSec, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(success);
    }

    /**
     * 利用lua脚本解决分布式锁的原子性问题
     */
    @Override
    public void unlock() {
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }


    //@Override
    //public void unlock() {
    //    // 判断这个锁是不是自己持有，以免误删
    //    String threadId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
    //    String curThreadId = ID_PREFIX + Thread.currentThread().getId();
    //    // 现在线程的id和redis中的id一致才可以删除
    //    if (curThreadId.equals(threadId)){
    //        // 有可能线程1在这里执行的时候被阻塞了，此时锁的时间到期了，此时线程2进来获得了锁
    //        // 由于他们两个锁的key是一样的，所以还是有可能导致误删，上面用lua脚本可以解决这个问题
    //        stringRedisTemplate.delete(KEY_PREFIX + name);
    //    }
    //}
}
