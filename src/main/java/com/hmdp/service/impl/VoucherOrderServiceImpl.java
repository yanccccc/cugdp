package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
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


    // 秒杀优惠卷
    @Override
    @Transactional
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
        // 开始扣减库存
        //乐观锁解决超卖问题
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", seckillVoucher.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("优惠券库存不足");
        }
        // 新建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(seckillVoucher.getVoucherId());
        // 订单id生成
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        save(voucherOrder);
        return Result.ok(orderId);
    }
}
