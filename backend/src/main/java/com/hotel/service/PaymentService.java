package com.hotel.service;

import com.hotel.dto.PaymentCallbackDTO;

/**
 * 支付回调：幂等处理，成功后订单 待支付 → 已确认
 */
public interface PaymentService {

    void handleCallback(PaymentCallbackDTO dto);
}
