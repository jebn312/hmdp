package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationContext;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_ORDER;
    static {
        SECKILL_ORDER = new DefaultRedisScript<>();
        SECKILL_ORDER.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_ORDER.setResultType(Long.class);
    }

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed()));
                    if(read.isEmpty() || read == null) continue;
                    MapRecord<String, Object, Object> record = read.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "c1", record.getId());
                } catch (Exception e) {
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    if(read == null || read.isEmpty()) break;
                    MapRecord<String, Object, Object> record = read.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理Pending订单异常", e);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }
    //@Override
    //public Result seckillVoucher(Long voucherId) {
    //    //查询优惠券
    //    SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
    //    if(seckillVoucher == null) {
    //        return Result.fail("优惠券不存在");
    //    }
    //    //判断过期时间
    //    if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) return Result.fail("秒杀未开始");
    //    if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) return Result.fail("秒杀已结束");
    //    //判断库存
    //    if(seckillVoucher.getStock() < 1) return Result.fail("库存不足");
    //    Long userId = UserHolder.getUser().getId();
    //    //锁颗粒度细化(在方法上同步会导致不同用户不能并发下单，这样可以保证同一用户并发下只能创建一单)
    //    //synchronized (userId.toString().intern()) {
    //    //    VoucherOrderServiceImpl proxy = applicationContext.getBean(VoucherOrderServiceImpl.class);
    //    //    return proxy.createVoucherOrder(voucherId);
    //    //}
    //    RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
    //    boolean isLock = lock.tryLock();
    //    if(!isLock) return Result.fail("请勿重复下单");
    //    try{
    //        VoucherOrderServiceImpl proxy = applicationContext.getBean(VoucherOrderServiceImpl.class);
    //        return proxy.createVoucherOrder(voucherId);
    //    } finally {
    //        lock.unlock();
    //    }
    //}

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + voucherOrder.getVoucherId());
        boolean isLock = lock.tryLock();
        if(!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try{
            VoucherOrderServiceImpl proxy = applicationContext.getBean(VoucherOrderServiceImpl.class);
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 优化秒杀：
     * 从lua脚本检查库存-扣减，返回
     *  lua:用户id，优惠券id，订单id
     *  返回：1-库存不足，2-重复下单，0-成功
     * 创建订单-使用redis消息队列(stream)实现
     * 接收到消息后创建订单
     */
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        Long l = stringRedisTemplate.execute(SECKILL_ORDER, Collections.emptyList(), userId.toString(), voucherId.toString(), String.valueOf(orderId));
        if(l != 0) return Result.fail(l == 1 ? "库存不足" : "重复下单");
        //TODO 保存阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        return Result.ok(orderId);
    }



    //将创建订单提取，防止并发情况都查不到创建订单(单机)
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Integer count = query().eq("user_id", voucherOrder.getUserId()).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if(count > 0) {
            log.error("用户已经购买过");
            return;
        }
        //扣减库存
        boolean isUpdate = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).update();
        if(!isUpdate) {
            log.error("库存不足");
            return;
        }
        //创建订单
        save(voucherOrder);
    }
}
