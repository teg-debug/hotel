package com.hotel.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.entity.BookingOrder;
import com.hotel.entity.PaymentLog;
import com.hotel.mapper.BookingOrderMapper;
import com.hotel.mapper.PaymentLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 支付对账任务。
 *
 * <p>对账不依赖具体网关：它只检查系统内部「订单状态」与「支付流水」是否自洽，
 * 这是任何网关接入后都必须成立的不变式，因此可以先行上线。
 * 接入真实网关后，可在此基础上补充网关账单的逐笔比对。</p>
 *
 * <p>检查两类偏差：已支付订单缺少成功流水；成功支付流水对应的订单未进入已支付状态。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconciliationTask {

    private static final int BATCH_SIZE = 200;
    private static final String SCHEDULE_LOCK_KEY = "lock:task:payment-reconcile";
    private static final long LOCK_WAIT_SECONDS = 0;

    private final BookingOrderMapper bookingOrderMapper;
    private final PaymentLogMapper paymentLogMapper;
    private final RedissonClient redissonClient;

    /** 每 10 分钟执行一次 */
    @Scheduled(fixedDelay = 600000, initialDelay = 120000)
    public void reconcile() {
        RLock lock = redissonClient.getLock(SCHEDULE_LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            checkPaidOrderWithoutPayLog();
            checkPayLogWithoutPaidOrder();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("支付对账任务执行异常", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 订单已进入已支付状态，但没有任何成功的支付流水 */
    private void checkPaidOrderWithoutPayLog() {
        List<BookingOrder> paidOrders = bookingOrderMapper.selectList(new LambdaQueryWrapper<BookingOrder>()
                .in(BookingOrder::getStatus, 1, 2, 4)
                .orderByDesc(BookingOrder::getId)
                .last("LIMIT " + BATCH_SIZE));
        for (BookingOrder order : paidOrders) {
            Long successCount = paymentLogMapper.selectCount(new LambdaQueryWrapper<PaymentLog>()
                    .eq(PaymentLog::getOrderId, order.getId())
                    .eq(PaymentLog::getBizType, PaymentLog.BIZ_PAY)
                    .eq(PaymentLog::getStatus, PaymentLog.STATUS_SUCCESS));
            if (successCount == null || successCount == 0) {
                log.error("对账异常：订单已支付但缺少成功支付流水 orderNo={} status={}",
                        order.getOrderNo(), order.getStatus());
            }
        }
    }

    /** 存在成功的支付流水，但对应订单未进入已支付状态 */
    private void checkPayLogWithoutPaidOrder() {
        List<PaymentLog> payLogs = paymentLogMapper.selectList(new LambdaQueryWrapper<PaymentLog>()
                .eq(PaymentLog::getBizType, PaymentLog.BIZ_PAY)
                .eq(PaymentLog::getStatus, PaymentLog.STATUS_SUCCESS)
                .orderByDesc(PaymentLog::getId)
                .last("LIMIT " + BATCH_SIZE));
        for (PaymentLog payLog : payLogs) {
            BookingOrder order = bookingOrderMapper.selectById(payLog.getOrderId());
            if (order == null) {
                log.error("对账异常：支付流水对应的订单不存在 payNo={} orderNo={}",
                        payLog.getPayNo(), payLog.getOrderNo());
                continue;
            }
            Integer status = order.getStatus();
            // 3-已取消需人工确认是否已退款，因此单独提示
            if (status != null && status == 3) {
                log.warn("对账提示：支付成功但订单已取消，请确认是否已完成退款 orderNo={} payNo={}",
                        order.getOrderNo(), payLog.getPayNo());
            } else if (status == null || status == 0) {
                log.error("对账异常：支付流水中标成功但订单仍为待支付 orderNo={} payNo={}",
                        order.getOrderNo(), payLog.getPayNo());
            }
        }
    }
}
