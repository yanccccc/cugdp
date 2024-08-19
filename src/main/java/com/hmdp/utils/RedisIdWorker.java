package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Author:CodeCan
 * Time:2024/8/19
 * 用redis生成全局唯一ID
 * ID是long类型，二进制位又64位
 * 最高位是0，不更改代表证书
 * 后31位代表时间戳，计算2022年1月1日0时0分0秒到现在的秒数
 * 最后32位代表秒内的计数器
 */
@Component
public class RedisIdWorker {
    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号的位数
     * 时间戳移动32位再和序列号进行或操作
     */
    private static final int COUNT_BITS = 32;

    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix){
        // 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long curSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = curSecond - BEGIN_TIMESTAMP;

        // 生成序列号
        // 每天都生成一个序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long increment = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        // 拼接
        return timestamp << COUNT_BITS | increment;
    }


    public static void main(String[] args) {
        // 计算2022年1月1日0时0分0秒的时间戳
        LocalDateTime localDateTime = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
        long epochSecond = localDateTime.atZone(ZoneOffset.UTC).toEpochSecond();
        System.out.println(epochSecond);
    }
}
