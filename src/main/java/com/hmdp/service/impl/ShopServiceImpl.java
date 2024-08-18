package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;

    // 根据店铺id查询店铺
    @Override
    public Result queryShopById(Long id) {
        // 缓存穿透
        // Shop shop = queryWithPassThrough(id);
        // 利用工具类进行缓存穿透
        // Shop shop = cacheClient.queryWithPassThrough(
        //        CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_NULL_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿问题
        // Shop shop = queryWithMutex(id);

        // 逻辑过期解决缓存击穿问题
        // Shop shop = queryWithLogicalExpire(id);
        // 利用工具类解决缓冲击穿
        Shop shop = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }

    // 利用逻辑过期解决缓存击穿问题
/*    public Shop queryWithLogicalExpire(Long id) {
        // 先走缓存
        String key = CACHE_SHOP_KEY + id;
        String redisShopJson = stringRedisTemplate.opsForValue().get(key);
        // 若缓存为空,则直接返回
        // 这里的逻辑是所有的数据都在缓存中，只有过期和未过期的
        // 没有数据不在缓存中这种情况
        if (StrUtil.isBlank(redisShopJson)){
            return null;
        }

        // 判断时间是否过期
        RedisData redisShop = JSONUtil.toBean(redisShopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisShop.getData(), Shop.class);
        LocalDateTime expireTime = redisShop.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())){
            // 如果未过期直接返回
            return shop;
        }
        // 过期了进行缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        if (tryLock(lockKey)){
            // 获取到互斥锁,检查缓存是否过期，如果缓存没过期则直接返回不需要缓存重建
            RedisData redisShopDoubleCheck = JSONUtil.toBean(redisShopJson, RedisData.class);
            Shop shopDoubleCheck = JSONUtil.toBean((JSONObject) redisShopDoubleCheck.getData(), Shop.class);
            LocalDateTime expireTimeDoubleCheck = redisShop.getExpireTime();
            if (expireTimeDoubleCheck.isAfter(LocalDateTime.now())){
                return shopDoubleCheck;
            }

            // 新开一个线程去重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {

                try {
                    saveShop2Redis(id,20L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });

        }
        return shop;
    }*/

    // 缓存预热，因为缓存里面没有数据需要放一些数据
    /*public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        // 设置过期时间放入到redis中
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }*/

    // 利用互斥锁解决缓存击穿问题
    /*public Shop queryWithMutex(Long id) {
        // 先走缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 若缓存不为空,直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
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
        // 获取互斥锁重建缓存，后来的数据库
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            if (!tryLock(lockKey)) {
                // 没获取到互斥锁,休眠等待一段时间获取
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 进行缓存重建
            // 缓存重建之前应该再次获取互斥锁
            // 因为在高并发场景下，有可能一个线程重建好了释放了互斥锁，
            // 但是这个线程在休眠完成之后又获取到了这个互斥锁，这样就不用进行缓存重建
            if (tryLock(lockKey)) {
                // 获取到互斥锁直接返回
                return JSONUtil.toBean(stringRedisTemplate.opsForValue().get(key), Shop.class);
            }
            shop = getById(id);
            // 模仿缓存重建的过程
            Thread.sleep(200);
            if (shop == null) {
                // 如果数据库中数据也为空，则写入到redis中，防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 写入缓存中 并且加上超时时间,防止数据的不一致性
            String json = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(key, json, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            unlock(lockKey);
        }
        return shop;
    }*/

    // 解决缓存穿透的代码
    /*public Shop queryWithPassThrough(Long id) {
        // 先走缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 若缓存不为空,直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
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
        Shop shop = getById(id);
        if (shop == null) {
            // 如果数据库中数据也为空，则写入到redis中，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 写入缓存中 并且加上超时时间,防止数据的不一致性
        String json = JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(key, json, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    // 获取互斥锁，true代表获取成功，false获取失败
    private boolean tryLock(String key) {
        // 尝试获取互斥锁，如果可以获取返回ture，并且将互斥锁的value设置为1，否则返回false
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    // 释放互斥锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }*/

    // 更新商铺
    //先更新数据库再删除缓存
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        // 查询redis中是否存在缓存
        String key = CACHE_SHOP_KEY + shop.getId();
        // 删除缓存
        stringRedisTemplate.delete(key);
        return Result.ok();
    }
}
