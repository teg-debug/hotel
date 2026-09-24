package com.hotel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 支付相关配置。
 *
 * <p>安全约束：{@code callbackSecret} 为空时，回调接口会拒绝一切请求，
 * 避免出现「未配置验签却仍然放行」的默认不安全状态。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.payment")
public class PaymentProperties {

    /** 网关实现：mock-本地模拟（仅开发/演示，不得在生产启用）；gateway-真实网关（需实现 PaymentGateway） */
    private String gateway = "gateway";

    /** 回调验签密钥，由支付网关下发；为空则拒绝所有回调 */
    private String callbackSecret = "";

    /** 回调时间戳容忍窗口（秒），超出该窗口的回调视为重放，默认 5 分钟 */
    private long timestampToleranceSeconds = 300;

    /** 回调来源 IP 白名单；为空表示不限制来源（仅建议在网关侧已做网络隔离时留空） */
    private List<String> allowedIps = new ArrayList<>();
}
