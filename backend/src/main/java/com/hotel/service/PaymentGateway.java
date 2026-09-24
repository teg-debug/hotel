package com.hotel.service;

import com.hotel.entity.BookingOrder;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 支付网关抽象。
 *
 * <p>业务层只依赖该接口，真实网关（微信/支付宝/银联）通过实现本接口接入，
 * 不需要改动订单与支付服务。本地模拟支付被收敛为 {@code mock} 实现，
 * 且由 {@code app.payment.gateway} 决定是否可用，避免模拟支付被带入生产。</p>
 */
public interface PaymentGateway {

    /** 网关标识，如 mock / wechat / alipay */
    String code();

    /** 是否支持本地模拟支付（仅 mock 实现返回 true） */
    boolean supportsSimulation();

    /**
     * 本地模拟支付：生成支付流水号并触发与真实回调完全一致的处理链路（含验签）。
     *
     * @return 支付流水号
     */
    String simulatePayment(BookingOrder order);

    /** 发起支付下单，返回给前端用于唤起支付收银台的参数 */
    PrepayPayload prepay(BookingOrder order);

    /**
     * 发起退款。
     *
     * @return 退款流水号
     */
    String refund(BookingOrder order, String reason);

    /** 支付下单结果 */
    record PrepayPayload(String orderNo, BigDecimal amount, Integer payType, Map<String, Object> params) {
    }
}
