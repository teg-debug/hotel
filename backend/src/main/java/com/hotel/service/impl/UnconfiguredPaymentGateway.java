package com.hotel.service.impl;

import com.hotel.common.BusinessException;
import com.hotel.entity.BookingOrder;
import com.hotel.service.PaymentGateway;

/**
 * 未接入真实支付网关时的占位实现（默认装配）。
 *
 * <p>它明确拒绝模拟支付与退款，使系统在缺少真实网关配置时保持「不能收钱」的诚实状态，
 * 而不是悄悄退回本地模拟入账。</p>
 */
public class UnconfiguredPaymentGateway implements PaymentGateway {

    @Override
    public String code() {
        return "unconfigured";
    }

    @Override
    public boolean supportsSimulation() {
        return false;
    }

    @Override
    public String simulatePayment(BookingOrder order) {
        throw new BusinessException("系统未启用模拟支付，请通过支付网关完成支付");
    }

    @Override
    public PrepayPayload prepay(BookingOrder order) {
        throw new BusinessException("尚未接入真实支付网关，无法发起支付");
    }

    @Override
    public String refund(BookingOrder order, String reason) {
        throw new BusinessException("尚未接入真实支付网关，无法发起退款，请联系运营人工处理");
    }
}
