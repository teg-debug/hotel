package com.hotel.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.common.BusinessException;
import com.hotel.common.PaymentSignUtil;
import com.hotel.config.PaymentProperties;
import com.hotel.dto.PaymentCallbackDTO;
import com.hotel.entity.BookingOrder;
import com.hotel.entity.PaymentLog;
import com.hotel.mapper.BookingOrderMapper;
import com.hotel.mapper.PaymentLogMapper;
import com.hotel.service.OrderTxService;
import com.hotel.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 支付回调处理。
 *
 * <p>安全顺序：先验签并校验时间窗与随机串，再进入业务处理。
 * 未配置验签密钥时直接拒绝全部回调，避免出现「默认放行」的不安全状态。</p>
 *
 * <p>幂等与状态校验统一放在分布式锁内，避免原先「检查在锁外」造成的并发窗口。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private static final long LOCK_WAIT_SECONDS = 3;
    private static final long LOCK_LEASE_SECONDS = 15;
    private static final String NONCE_KEY_PREFIX = "pay:nonce:";

    private final BookingOrderMapper bookingOrderMapper;
    private final PaymentLogMapper paymentLogMapper;
    private final RedissonClient redissonClient;
    private final OrderTxService orderTxService;
    private final PaymentProperties paymentProperties;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void handleCallback(PaymentCallbackDTO dto) {
        // 1. 验签 + 时间窗 + 防重放
        try {
            verifyCallback(dto);
        } catch (BusinessException e) {
            // 回调被拒需要留痕：这是判断「是否有人在伪造回调」的主要线索
            log.warn("支付回调校验未通过 orderNo={} payNo={} 原因={}",
                    dto.getOrderNo(), dto.getPayNo(), e.getMessage());
            throw e;
        }

        // 2. 订单存在性校验
        BookingOrder order = bookingOrderMapper.selectOne(
                new LambdaQueryWrapper<BookingOrder>().eq(BookingOrder::getOrderNo, dto.getOrderNo()));
        if (order == null) {
            throw new BusinessException("订单不存在");
        }

        // 3. 锁内完成幂等判断与状态流转
        String lockKey = "lock:pay:" + order.getOrderNo();
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                throw new BusinessException("支付处理中，请稍后重试");
            }
            applyCallback(order, dto);
            log.info("支付回调处理完成 orderNo={} payNo={} 回调状态={} 金额={}",
                    order.getOrderNo(), dto.getPayNo(), dto.getStatus(), dto.getAmount());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("系统繁忙，请稍后重试");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void applyCallback(BookingOrder order, PaymentCallbackDTO dto) {
        // 支付失败：仅记录失败流水，订单保持「待支付」可重新支付
        if (!Integer.valueOf(1).equals(dto.getStatus())) {
            log.warn("支付失败回调 orderNo={} payNo={} 金额={}",
                    order.getOrderNo(), dto.getPayNo(), dto.getAmount());
            recordFailure(order, dto);
            return;
        }

        Integer status = order.getStatus();
        if (status == null || status == 0) {
            // 订单待支付：走确认流程（内部再做流水幂等与金额校验）
            orderTxService.confirmOrder(order, dto);
            return;
        }
        if (status == 3) {
            throw new BusinessException("订单已取消，无法支付");
        }
        // 1/2/4 表示订单已支付。同一流水号的重复回调属正常幂等；
        // 不同流水号则可能是重复支付，记入告警并作为成功返回，
        // 避免网关因报错而无限重试，同时把异常暴露给对账任务与运维。
        Long succeeded = paymentLogMapper.selectCount(new LambdaQueryWrapper<PaymentLog>()
                .eq(PaymentLog::getPayNo, dto.getPayNo())
                .eq(PaymentLog::getBizType, PaymentLog.BIZ_PAY)
                .eq(PaymentLog::getStatus, PaymentLog.STATUS_SUCCESS));
        if (succeeded == null || succeeded == 0) {
            log.warn("检测到订单重复支付，需人工核对或退款 orderNo={} payNo={} amount={} orderStatus={}",
                    order.getOrderNo(), dto.getPayNo(), dto.getAmount(), status);
        }
    }

    /** 验签、时间窗与随机串校验，任一项不通过直接拒绝 */
    private void verifyCallback(PaymentCallbackDTO dto) {
        String secret = paymentProperties.getCallbackSecret();
        if (secret == null || secret.isBlank()) {
            throw new BusinessException("支付回调未配置验签密钥，已拒绝该请求");
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(dto.getTimestamp());
        } catch (NumberFormatException e) {
            throw new BusinessException("支付回调时间戳格式错误");
        }
        long toleranceMillis = paymentProperties.getTimestampToleranceSeconds() * 1000L;
        if (Math.abs(System.currentTimeMillis() - timestamp) > toleranceMillis) {
            throw new BusinessException("支付回调已超出有效时间窗口");
        }

        if (!PaymentSignUtil.verify(dto.toSignParams(), dto.getSign(), secret)) {
            log.warn("支付回调验签失败 orderNo={} payNo={}", dto.getOrderNo(), dto.getPayNo());
            throw new BusinessException("支付回调验签失败");
        }

        // 验签通过后再占用随机串，避免未通过验签的请求污染去重键
        Boolean fresh = stringRedisTemplate.opsForValue().setIfAbsent(
                NONCE_KEY_PREFIX + dto.getNonce(), "1",
                paymentProperties.getTimestampToleranceSeconds(), TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(fresh)) {
            throw new BusinessException("支付回调重复提交");
        }
    }

    /**
     * 记录失败回调。
     *
     * <p>流水号上存在唯一键，因此同一流水号重复到达时更新原行，
     * 保证后续同流水号的成功回调不会因唯一键冲突而无法入账。</p>
     */
    private void recordFailure(BookingOrder order, PaymentCallbackDTO dto) {
        PaymentLog existing = paymentLogMapper.selectOne(new LambdaQueryWrapper<PaymentLog>()
                .eq(PaymentLog::getPayNo, dto.getPayNo())
                .eq(PaymentLog::getBizType, PaymentLog.BIZ_PAY)
                .last("LIMIT 1"));
        if (existing != null) {
            existing.setStatus(PaymentLog.STATUS_FAILED);
            existing.setAmount(dto.getAmount());
            existing.setPayType(dto.getPayType() == null ? 0 : dto.getPayType());
            existing.setCallbackTime(LocalDateTime.now());
            paymentLogMapper.updateById(existing);
            return;
        }
        PaymentLog failure = new PaymentLog();
        failure.setOrderId(order.getId());
        failure.setOrderNo(order.getOrderNo());
        failure.setPayNo(dto.getPayNo());
        failure.setBizType(PaymentLog.BIZ_PAY);
        failure.setPayType(dto.getPayType() == null ? 0 : dto.getPayType());
        failure.setAmount(dto.getAmount());
        failure.setStatus(PaymentLog.STATUS_FAILED);
        failure.setCallbackTime(LocalDateTime.now());
        paymentLogMapper.insert(failure);
    }
}
