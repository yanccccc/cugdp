package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * Author:CodeCan
 * Time:2024/8/18
 * 封装Redis缓存工具类
 */
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    // 将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题

    /**
     * @param keyPrefix  key的前缀
     * @param id         需要查询的id
     * @param type       需要查询的entity类型
     * @param dbFallBack entity类型
     * @param time       时间长度
     * @param unit       时间单位
     * @param <R>        ID类型
     * @param <T>        entity类型
     * @return 需要查询的entity类型
     */
    public <R, T> R queryWithPassThrough(
            String keyPrefix, T id, Class<R> type, Function<T, R> dbFallBack, Long time, TimeUnit unit) {
        // 先走缓存
        String key = keyPrefix + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 若缓存不为空,直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, type);
        }

        // 为了解决缓存穿透问题，选择使用缓存空字符串的方法来解决
        // 缓存穿透就是用户查询redis和数据库中不存在的数据，给数据库造成大量的压力
        // 解决方法是，当用户访问一个数据库中不存在的数据时，redis缓存一个空字符串，设置一个过期时间
        // 前面是redis中有数据的情况，直接返回
        // 现在是redis中查询到数据库为空字符串的情况，也就是数据库中和redis中都没有数据，返回一个错误
        if ("".equals(shopJson)) {
            return null;
        }

        // 若缓存为空查询数据库
        R r = dbFallBack.apply(id);
        if (r == null) {
            // 如果数据库中数据也为空，则写入到redis中，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 写入缓存中 并且加上超时时间,防止数据的不一致性
        String json = JSONUtil.toJsonStr(r);
        this.set(key, json, time, unit);
        return r;
    }

    // 根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题

    /**
     * @param keyPrefix  key的前缀
     * @param id         需要查询的id
     * @param type       需要查询的entity类型
     * @param dbFallBack entity类型
     * @param time       时间长度
     * @param unit       时间单位
     * @param <R>        ID类型
     * @param <T>        entity类型
     * @return 需要查询的entity类型
     */
    public <R, T> R queryWithLogicalExpire(
            String keyPrefix, T id, Class<R> type, Function<T, R> dbFallBack, Long time, TimeUnit unit) {
        // 先走缓存
        String key = keyPrefix + id;
        String redisJson = stringRedisTemplate.opsForValue().get(key);
        // 若缓存为空,则直接返回
        // 这里的逻辑是所有的数据都在缓存中，只有过期和未过期的
        // 没有数据不在缓存中这种情况
        if (StrUtil.isBlank(redisJson)) {
            return null;
        }

        // 判断时间是否过期
        RedisData redisR = JSONUtil.toBean(redisJson, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisR.getData(), type);
        LocalDateTime expireTime = redisR.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 如果未过期直接返回
            return r;
        }
        // 过期了进行缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)) {
            // 获取到互斥锁,检查缓存是否过期，如果缓存没过期则直接返回不需要缓存重建
            RedisData redisShopDoubleCheck = JSONUtil.toBean(redisJson, RedisData.class);
            R rDoubleCheck = JSONUtil.toBean((JSONObject) redisShopDoubleCheck.getData(), type);
            LocalDateTime expireTimeDoubleCheck = redisR.getExpireTime();
            if (expireTimeDoubleCheck.isAfter(LocalDateTime.now())) {
                return rDoubleCheck;
            }

            // 新开一个线程去重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {

                try {
                    R r1 = dbFallBack.apply(id);
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });

        }
        return r;
    }


    // 获取互斥锁，true代表获取成功，false获取失败
    private boolean tryLock(String key) {
        // 尝试获取互斥锁，如果可以获取返回ture，并且将互斥锁的value设置为1，否则返回false
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    // 释放互斥锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}
