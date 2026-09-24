package com.hotel.config;

import com.hotel.service.PaymentGateway;
import com.hotel.service.PaymentService;
import com.hotel.service.impl.MockPaymentGateway;
import com.hotel.service.impl.UnconfiguredPaymentGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 支付网关装配：由 {@code app.payment.gateway} 决定使用哪一种实现。
 *
 * <p>两种实现互斥，避免出现「模拟网关与真实网关同时存在」的歧义装配。</p>
 */
@Configuration
public class PaymentConfig {

    /** 本地模拟网关：仅当 app.payment.gateway=mock 时装配 */
    @Bean
    @ConditionalOnProperty(prefix = "app.payment", name = "gateway", havingValue = "mock")
    public PaymentGateway mockPaymentGateway(PaymentService paymentService, PaymentProperties properties) {
        return new MockPaymentGateway(paymentService, properties);
    }

    /** 默认占位网关：未接入真实网关时保持「不能收钱」的显式状态 */
    @Bean
    @ConditionalOnProperty(prefix = "app.payment", name = "gateway", havingValue = "gateway", matchIfMissing = true)
    public PaymentGateway unconfiguredPaymentGateway() {
        return new UnconfiguredPaymentGateway();
    }
}
