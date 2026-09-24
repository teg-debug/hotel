package com.hotel.service.impl;

import com.hotel.common.BusinessException;
import com.hotel.config.PaymentProperties;
import com.hotel.dto.PaymentCallbackDTO;
import com.hotel.entity.BookingOrder;
import com.hotel.service.PaymentGateway;
import com.hotel.service.PaymentService;
import com.hotel.utils.OrderNoGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 本地模拟支付网关。
 *
 * <p>只由 {@code app.payment.gateway=mock} 显式启用，且模拟支付同样走「签名 → 回调 → 验签 → 入账」
 * 的真实链路，保证开发环境验证过的处理逻辑与生产一致。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class MockPaymentGateway implements PaymentGateway {

    private final PaymentService paymentService;
    private final PaymentProperties paymentProperties;

    @Override
    public String code() {
        return "mock";
    }

    @Override
    public boolean supportsSimulation() {
        return true;
    }

    @Override
    public String simulatePayment(BookingOrder order) {
        String secret = paymentProperties.getCallbackSecret();
        if (secret == null || secret.isBlank()) {
            throw new BusinessException("模拟支付需要配置 app.payment.callback-secret 后才能完成验签");
        }
        String payNo = OrderNoGenerator.generatePayNo();
        PaymentCallbackDTO callback = new PaymentCallbackDTO();
        callback.setOrderNo(order.getOrderNo());
        callback.setPayNo(payNo);
        callback.setPayType(0);
        callback.setAmount(order.getTotalAmount());
        callback.setStatus(1);
        callback.setTimestamp(String.valueOf(System.currentTimeMillis()));
        callback.setNonce(OrderNoGenerator.generatePayNo());
        callback.sign(secret);

        log.info("模拟支付网关发起回调 orderNo={} payNo={}", order.getOrderNo(), payNo);
        paymentService.handleCallback(callback);
        return payNo;
    }

    @Override
    public PrepayPayload prepay(BookingOrder order) {
        throw new BusinessException("当前为模拟支付网关，不支持向真实收银台下单");
    }

    @Override
    public String refund(BookingOrder order, String reason) {
        // 模拟网关的退款直接返回退款流水号，由订单事务服务统一落退款流水
        log.info("模拟支付网关受理退款 orderNo={} reason={}", order.getOrderNo(), reason);
        return OrderNoGenerator.generatePayNo();
    }
}
