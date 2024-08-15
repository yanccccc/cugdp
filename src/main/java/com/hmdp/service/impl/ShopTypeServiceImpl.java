package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_LIST_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 查询店铺类型
    @Override
    public Result queryTypeList() {
        // 从redis中查询
        String key = CACHE_SHOP_LIST_KEY;
        Long size = stringRedisTemplate.opsForList().size(key);
        if (size != null && size > 0) {
            List<String> shopTypeJson = stringRedisTemplate.opsForList().range(key, 0, size);
            // 将List<String>转化成List<ShopType>
            List<ShopType> shopTypes = shopTypeJson.stream().map(s -> JSONUtil.toBean(s, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(shopTypes);
        }

        // 如果没有则查询数据库
        List<ShopType> typeList = list();
        if (typeList == null || typeList.isEmpty()) {
            return Result.fail("没有店铺类型");
        }
        // 存入到redis中
        List<String> shopTypeJson = typeList.stream().map(JSONUtil::toJsonStr).collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(key, shopTypeJson);
        return Result.ok(typeList);
    }
}
