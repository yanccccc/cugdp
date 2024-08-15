package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 根据店铺id查询店铺
    @Override
    public Result queryShopById(Long id) {
        // 先走缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 若缓存不为空
        if (StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        // 若缓存为空查询数据库
        Shop shop = getById(id);
        if (shop == null){
            return Result.fail("输入的id有误");
        }
        // 写入缓存中 并且加上超时时间
        String json = JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(key,json,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }

    // 更新商铺
    //先更新数据库再删除缓存
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        if (shop.getId() == null){
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
