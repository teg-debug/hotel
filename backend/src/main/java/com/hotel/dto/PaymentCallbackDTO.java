package com.hotel.dto;

import com.hotel.common.PaymentSignUtil;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

/**
 * 支付回调请求参数。
 *
 * <p>回调必须携带签名、时间戳与随机串：签名用于确认请求来自支付网关，
 * 时间戳与随机串配合服务端的容忍窗口与去重键，防止重放。</p>
 */
@Data
public class PaymentCallbackDTO {

    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    /** 支付流水号（全局唯一，幂等依据） */
    @NotBlank(message = "支付流水号不能为空")
    private String payNo;

    /** 支付方式：0-模拟支付 1-微信 2-支付宝 */
    private Integer payType = 0;

    /** 支付金额 */
    @NotNull(message = "支付金额不能为空")
    private BigDecimal amount;

    /** 支付状态：1-支付成功 0-支付失败 */
    @NotNull(message = "支付状态不能为空")
    private Integer status;

    /** 回调时间戳（毫秒字符串） */
    @NotBlank(message = "回调时间戳不能为空")
    private String timestamp;

    /** 随机串，服务端按时间窗去重，防止重放 */
    @NotBlank(message = "回调随机串不能为空")
    private String nonce;

    /** 签名（HMAC-SHA256 小写十六进制），不参与自身的签名计算 */
    @NotBlank(message = "回调签名不能为空")
    private String sign;

    /**
     * 待签名的业务参数：签名方与校验方共用本方法，避免两侧字段口径漂移。
     */
    public Map<String, String> toSignParams() {
        Map<String, String> params = new TreeMap<>();
        params.put("orderNo", orderNo);
        params.put("payNo", payNo);
        params.put("payType", String.valueOf(payType == null ? 0 : payType));
        params.put("amount", amount == null ? "" : amount.toPlainString());
        params.put("status", String.valueOf(status));
        params.put("timestamp", timestamp);
        params.put("nonce", nonce);
        return params;
    }

    /** 用指定密钥完成签名并写入 {@code sign} 字段 */
    public void sign(String secret) {
        this.sign = PaymentSignUtil.sign(toSignParams(), secret);
    }
}
