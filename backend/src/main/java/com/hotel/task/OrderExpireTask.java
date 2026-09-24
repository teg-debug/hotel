package com.hotel.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.entity.BookingOrder;
import com.hotel.mapper.BookingOrderMapper;
import com.hotel.service.OrderTxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 超时关单定时任务：扫描「待支付且已过期」的订单并自动取消。
 *
 * <p>用分布式调度锁保证多实例部署时同一批订单只会被一个实例处理。
 * 房源缓存的失效已由版本号机制优化为 O(1) 操作，因此在循环内逐单失效不再有性能问题。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpireTask {

    private static final int BATCH_SIZE = 500;
    private static final String SCHEDULE_LOCK_KEY = "lock:task:order-expire";
    private static final long LOCK_WAIT_SECONDS = 0;

    private final BookingOrderMapper bookingOrderMapper;
    private final OrderTxService orderTxService;
    private final RedissonClient redissonClient;

    /** 每 30 秒执行一次（fixedDelay：上次执行完成后间隔 30s） */
    @Scheduled(fixedDelay = 30000, initialDelay = 10000)
    public void cancelExpiredOrders() {
        RLock lock = redissonClient.getLock(SCHEDULE_LOCK_KEY);
        boolean locked = false;
        try {
            // waitTime=0：抢不到锁说明其他实例正在处理，本次直接跳过，无需排队
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            int cancelled = doCancelExpiredOrders();
            if (cancelled > 0) {
                log.info("自动取消超时订单 {} 笔", cancelled);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private int doCancelExpiredOrders() {
        List<BookingOrder> expiredOrders = bookingOrderMapper.selectList(new LambdaQueryWrapper<BookingOrder>()
                .eq(BookingOrder::getStatus, 0)                       // 待支付
                .lt(BookingOrder::getExpireTime, LocalDateTime.now()) // 已过期
                .last("LIMIT " + BATCH_SIZE));

        int cancelled = 0;
        for (BookingOrder order : expiredOrders) {
            try {
                if (orderTxService.cancelOrder(order, "超时未支付，系统自动取消")) {
                    cancelled++;
                }
            } catch (Exception e) {
                // 单条失败不影响其余订单
                log.error("自动取消过期订单失败, orderNo={}", order.getOrderNo(), e);
            }
        }
        return cancelled;
    }
}
