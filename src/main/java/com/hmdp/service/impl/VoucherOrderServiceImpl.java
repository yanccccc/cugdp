package com.hmdp.service.impl;

import cn.hutool.extra.spring.SpringUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private SpringUtil springUtil;


    // 秒杀优惠卷
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 查询数据库中是否有这个秒杀券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (Objects.isNull(seckillVoucher)) {
            return Result.fail("优惠券不存在");
        }
        // 查询秒杀券时间是否开始
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(seckillVoucher.getBeginTime())) {
            return Result.fail("秒杀时间还没开始");
        }
        // 查询秒杀券时间是否结束
        if (now.isAfter(seckillVoucher.getEndTime())) {
            return Result.fail("秒杀时间已经结束");
        }
        // 查询优惠券是否还有库存
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("优惠券库存不足");
        }
        Long id = UserHolder.getUser().getId();
        // 悲观锁解决一人一单问题
        // 因为这是插入数据不是修改数据，所以要使用悲观锁解决
        synchronized (id.toString().intern()){
            // 由于这里是this.createVoucherOrder也就是这个类调用内部的方法，spring的事务会失效
            return SpringUtil.getBean(IVoucherOrderService.class).createVoucherOrder(voucherId);
            // 也可以这样
            // IVoucherOrderService iVoucherOrderService = (IVoucherOrderService) AopContext.currentProxy();
            // return iVoucherOrderService.createVoucherOrder(voucherId);
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId){
        // 一人一单，数据库里面user_id和voucher_id组合只能有一条数据
        Long id = UserHolder.getUser().getId();
        int count = query().eq("user_id", id).eq("voucher_id", voucherId).count();
        if (count > 0){
            return Result.fail("一人只允许抢一张券");
        }

        // 开始扣减库存
        //乐观锁解决超卖问题
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("优惠券库存不足");
        }
        // 新建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setUserId(id);
        voucherOrder.setVoucherId(voucherId);
        // 订单id生成
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
