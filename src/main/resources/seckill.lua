-- 参数列表
-- 优惠券id
local voucherId = ARGV[1]
-- 用户id
local userId = ARGV[2]
-- 订单id
local orderId = ARGV[3]

-- 优惠券的库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 订单的key
local orderKey = 'seckill:order:' .. voucherId

-- 判断是否还有库存
if (tonumber(redis.call('get', stockKey)) <= 0) then
    -- 库存不足，返回1
    return 1
end
if (redis.call('sismember', orderKey, userId) == 1) then
    -- 用户已经下过单，返回2
    return 2
end
-- 成功下单，扣减库存
redis.call('incrby', stockKey, -1)
-- 保存下单过的用户
redis.call('sadd', orderKey, userId)
-- 发送消息到消息队列中 XADD stream.orders * k1 v1 k2 v2
redis.call('xadd', 'stream.orders', '*', 'voucherId', voucherId, 'userId', userId, 'id', orderId)
-- 成功下单返回0
return 0