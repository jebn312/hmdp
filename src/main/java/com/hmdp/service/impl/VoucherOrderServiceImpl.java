package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
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

    @Resource
    private ApplicationContext applicationContext;

    /**
     * 秒杀优惠券
     *
     * @param voucherId 优惠券id
     * @return 订单id
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if(seckillVoucher == null) {
            return Result.fail("优惠券不存在");
        }
        //判断过期时间
        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) return Result.fail("秒杀未开始");
        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) return Result.fail("秒杀已结束");
        //判断库存
        if(seckillVoucher.getStock() < 1) return Result.fail("库存不足");
        Long userId = UserHolder.getUser().getId();
        //锁颗粒度细化(在方法上同步会导致不同用户不能并发下单，这样可以保证同一用户并发下只能创建一单)
        synchronized (userId.toString().intern()) {
            VoucherOrderServiceImpl proxy = applicationContext.getBean(VoucherOrderServiceImpl.class);
            return proxy.createVoucherOrder(voucherId);
        }

    }

    //将创建订单提取，防止并发情况都查不到创建订单(单机)
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(count > 0) return Result.fail("用户已经购买过");
        //扣减库存
        boolean isUpdate = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
        if(!isUpdate) return Result.fail("库存不足");
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.nextId("order"));
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(voucherOrder.getVoucherId());
    }
}
