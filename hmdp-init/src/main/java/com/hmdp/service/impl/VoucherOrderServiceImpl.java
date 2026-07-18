package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService iSeckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;
    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //阻塞队列
    private BlockingQueue<VoucherOrder> orderTask = new ArrayBlockingQueue<>(1024 * 1024);
    //代理对象
    private IVoucherOrderService proxy;
    //脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    //初始化脚本文件
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @PostConstruct //类一初始化就执行
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                //从阻塞队列中取出订单信息
                try {
                    VoucherOrder order = orderTask.take();
                    //创建订单
                    handleVoucherOrder(order);
                } catch (InterruptedException e) {
                    log.error("订单处理异常");
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder order) {
        //获取用户id
        Long userId = order.getUserId();
        //获取锁（分布式锁解决集群下线程并发安全问题）
        //Redisson可重入锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("一个用户只能下单一次");
        }
        try {
            proxy.createVoucherOrder(order);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.执行lua脚本
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());
        int r = result.intValue();
        //2.判断结果是否为0
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "一个用户只能下单一次");
        }
        //创建订单
        VoucherOrder order = new VoucherOrder();
        //生成订单id
        long orderId = redisIdWorker.nextId("order");
        order.setId(orderId);
        //生成用户id
        order.setUserId(userId);
        //生成优惠券id
        order.setVoucherId(voucherId);
        //将优惠券id,用户id,订单id封装成订单存入阻塞队列
        orderTask.add(order);
        //初始化代理对象：主线程获取代理对象，防止子线程无法使用代理对象
        IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder order) {
        Long voucherId = order.getVoucherId();
        //扣除库存(乐观锁防止超买)
        boolean success = iSeckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
        }
        save(order);
    }
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠券信息
//        SeckillVoucher seckillVoucher = iSeckillVoucherService.getById(voucherId);
//        //判断秒杀是否开始
//        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀还未开始!");
//        }
//        //判断秒杀是否结束
//        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束！");
//        }
//        //判断库存是否充足
//        if(seckillVoucher.getStock() < 1){
//            return Result.fail("库存不足");
//        }
//        //获取锁（分布式锁解决集群下线程并发安全问题）
//        Long userId = UserHolder.getUser().getId();
//        //Redisson可重入锁
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //SimpleRedisLock lock = new SimpleRedisLock("order" + userId , stringRedisTemplate);
//        try {
//            boolean isLock = lock.tryLock();
//            if(!isLock){
//                return Result.fail("一个用户只能下单一次");
//            }
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            //释放锁
//            lock.unlock();
//        }
//
//    }
//    @Transactional
//    public Result createVoucherOrder(Long voucherId){
//        //一人一单
//        Long userId = UserHolder.getUser().getId();
//        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        if(count > 0){
//            return Result.fail("用户已经购买过一次");
//        }
//        //扣除库存(乐观锁防止超买)
//        boolean success = iSeckillVoucherService.update()
//                .setSql("stock = stock - 1")
//                .eq("voucher_id", voucherId)
//                .gt("stock", 0)
//                .update();
//        if(!success){
//            return Result.fail("库存不足");
//        }
//        //创建订单
//        VoucherOrder order = new VoucherOrder();
//        //生成订单id
//        long orderId = redisIdWorker.nextId("order");
//        order.setId(orderId);
//        //生成用户id
//        order.setUserId(userId);
//        //生成优惠券id
//        order.setVoucherId(voucherId);
//        save(order);
//        return Result.ok(orderId);
//    }
}
