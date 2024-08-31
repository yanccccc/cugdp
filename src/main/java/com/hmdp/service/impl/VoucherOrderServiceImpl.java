package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 阻塞队列
    // 可以用redis中的stream来实现消息队列
    // private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    //异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private IVoucherOrderService proxy;

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        private static final String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    // 获取消息队列中的订单消息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"), StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)), StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                    // 判断订单信息是否为空
                    if (records == null || records.isEmpty()) {
                        // 为空则进行下一次循环到收到消息
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = records.get(0);
                    Map<Object, Object> value = record.getValue();
                    // 转化成对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 创建订单
                    handleVoucherOrder(voucherOrder);
                    // 确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    //处理异常消息
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                    // pending-list中的消息都是没有ack的消息
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"), StreamReadOptions.empty().count(1), StreamOffset.create(queueName, ReadOffset.from("0")));
                    // 判断订单信息是否为空
                    if (records == null || records.isEmpty()) {
                        // 为空则代表pending-list中没有消息
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = records.get(0);
                    Map<Object, Object> value = record.getValue();
                    // 转化成对象
                    VoucherOrder voucherOrder = new VoucherOrder();
                    BeanUtil.fillBeanWithMap(value, voucherOrder, true);
                    // 创建订单
                    handleVoucherOrder(voucherOrder);
                    // 确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pendding-list订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    // 处理创建订单的业务
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 判断是否获取到锁
        // 直接用redisson分布式锁不用自己实现的分布式锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean success = lock.tryLock();
        if (!success) {
            // 没有获取到则直接返回
            log.error("一人只允许抢一张券");
            return;
        }
        // 由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }


    // 线程池需要处理的任务
    //private class VoucherOrderHandler implements Runnable {
    //
    //    @Override
    //    public void run() {
    //        while (true) {
    //            // 不停的从阻塞队列中拿任务执行
    //            try {
    //                // 1.获取队列中的订单信息
    //                VoucherOrder voucherOrder = orderTasks.take();
    //                // 2.创建订单
    //                handleVoucherOrder(voucherOrder);
    //            } catch (InterruptedException e) {
    //                log.error("处理秒杀订单业务出现异常");
    //            }
    //        }
    //    }
    //
    //    // 处理创建订单的业务
    //    private void handleVoucherOrder(VoucherOrder voucherOrder) {
    //        Long userId = voucherOrder.getUserId();
    //        // 判断是否获取到锁
    //        // 直接用redisson分布式锁不用自己实现的分布式锁
    //        RLock lock = redissonClient.getLock("lock:order:" + userId);
    //        // 尝试获取锁
    //        boolean success = lock.tryLock();
    //        if (!success) {
    //            // 没有获取到则直接返回
    //            log.error("一人只允许抢一张券");
    //            return;
    //        }
    //        // 由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
    //        try {
    //            proxy.createVoucherOrder(voucherOrder);
    //        } finally {
    //            // 释放锁
    //            lock.unlock();
    //        }
    //    }
    //}


    // 秒杀优惠卷
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 用户id
        Long userId = UserHolder.getUser().getId();
        // 订单id
        long orderId = redisIdWorker.nextId("order");
        // 使用lua脚本来解决秒杀问题
        // 1.获取lua脚本的结果
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString(), String.valueOf(orderId));
        int r = result.intValue();
        // 2.如果返回值为1则说明库存不足
        if (r == 1) {
            return Result.fail("库存不足");
        }
        // 3.返回值为2则说明用户已经抢购过
        if (r == 2) {
            return Result.fail("您已经抢购过这张优惠券了，一人只能抢一张");
        }
        // 4.返回值为0则说明用户抢购成功

        // 5.获取代理对象，因为处理订单到数据库的业务不是主线程而是其他的线程
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 6.返回订单id
        return Result.ok(orderId);

    }

    //// 秒杀优惠卷
    //@Override
    //public Result seckillVoucher(Long voucherId) {
    //    // 查询数据库中是否有这个秒杀券
    //    SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
    //    if (Objects.isNull(seckillVoucher)) {
    //        return Result.fail("优惠券不存在");
    //    }
    //    // 查询秒杀券时间是否开始
    //    LocalDateTime now = LocalDateTime.now();
    //    if (now.isBefore(seckillVoucher.getBeginTime())) {
    //        return Result.fail("秒杀时间还没开始");
    //    }
    //    // 查询秒杀券时间是否结束
    //    if (now.isAfter(seckillVoucher.getEndTime())) {
    //        return Result.fail("秒杀时间已经结束");
    //    }
    //    // 查询优惠券是否还有库存
    //    if (seckillVoucher.getStock() < 1) {
    //        return Result.fail("优惠券库存不足");
    //    }
    //    Long id = UserHolder.getUser().getId();
    //
    //    // 判断是否获取到锁
    //    //SimpleRedisLock redisLock = new SimpleRedisLock(stringRedisTemplate, "order:" + id);
    //    //boolean success = redisLock.tryLock(1200L);
    //    // 直接用redisson分布式锁不用自己实现的分布式锁
    //    RLock lock = redissonClient.getLock("lock:order:" + id);
    //    boolean success = lock.tryLock();
    //
    //    if (!success){
    //        // 没有获取到则直接返回
    //        return Result.fail("一人只允许抢一张券");
    //    }
    //    //
    //    try {
    //        return SpringUtil.getBean(IVoucherOrderService.class).createVoucherOrder(voucherId);
    //    } finally {
    //        // 释放锁
    //        lock.unlock();
    //    }
    //
    //
    //    /*// 悲观锁解决一人一单问题，在集群模式下还是会有问题，用上面redis的分布式锁可以解决
    //    // 因为这是插入数据不是修改数据，所以要使用悲观锁解决
    //    synchronized (id.toString().intern()){
    //        // 由于这里是this.createVoucherOrder也就是这个类调用内部的方法，spring的事务会失效
    //        return SpringUtil.getBean(IVoucherOrderService.class).createVoucherOrder(voucherId);
    //        // 也可以这样
    //        // IVoucherOrderService iVoucherOrderService = (IVoucherOrderService) AopContext.currentProxy();
    //        // return iVoucherOrderService.createVoucherOrder(voucherId);
    //    }*/
    //}

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单，数据库里面user_id和voucher_id组合只能有一条数据
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("一人只允许抢一张券");
            return;
        }

        // 开始扣减库存
        //乐观锁解决超卖问题
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).update();
        if (!success) {
            log.error("优惠券库存不足");
            return;
        }
        // 新建订单
        save(voucherOrder);
    }
}
