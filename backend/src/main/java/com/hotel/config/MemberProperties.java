package com.hotel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会员折扣配置：把原先写死在服务里的折扣率外置，避免与数据库字段形成两套口径。
 *
 * <p>键为会员等级（0-普通 1-银卡 2-金卡），值为折扣率。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.member")
public class MemberProperties {

    private Map<String, BigDecimal> discount = new LinkedHashMap<>(Map.of(
            "0", BigDecimal.ONE,
            "1", new BigDecimal("0.95"),
            "2", new BigDecimal("0.88")));

    /** 取指定会员等级的折扣率，未配置的等级按原价处理 */
    public BigDecimal discountOf(Integer level) {
        String key = String.valueOf(level == null ? 0 : level);
        BigDecimal value = discount.get(key);
        return value == null ? BigDecimal.ONE : value;
    }
}
