package com.hotel.controller;

import com.hotel.common.BusinessException;
import com.hotel.common.Result;
import com.hotel.config.PaymentProperties;
import com.hotel.dto.PaymentCallbackDTO;
import com.hotel.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付回调：由支付网关服务端调用，因此不在 JWT 拦截范围内。
 *
 * <p>该接口的入口防护由三层构成：来源 IP 白名单（本类）、
 * 请求签名与时间窗校验、随机串防重放（PaymentService）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentProperties paymentProperties;

    @PostMapping("/callback")
    public Result<Void> callback(@Valid @RequestBody PaymentCallbackDTO dto, HttpServletRequest request) {
        verifySourceIp(request);
        paymentService.handleCallback(dto);
        return Result.success();
    }

    /** 来源 IP 白名单校验；未配置白名单时不限制来源，但仍需通过验签 */
    private void verifySourceIp(HttpServletRequest request) {
        if (paymentProperties.getAllowedIps() == null || paymentProperties.getAllowedIps().isEmpty()) {
            return;
        }
        String clientIp = resolveClientIp(request);
        if (!paymentProperties.getAllowedIps().contains(clientIp)) {
            log.warn("支付回调来源 IP 不在白名单内 ip={}", clientIp);
            throw new BusinessException("支付回调来源不被允许");
        }
    }

    /** 依次尝试反向代理头，取不到时回退到连接的远端地址 */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
