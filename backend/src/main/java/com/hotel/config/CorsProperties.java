package com.hotel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨域来源配置。
 *
 * <p>默认空列表表示不开启跨域。前端在开发环境通过 Vite 代理访问后端，属于同源请求，
 * 本就无需跨域；把默认值设为空可以避免「通配来源 + 携带凭据」被带到生产。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /** 允许的来源，支持通配写法（如 http://localhost:*） */
    private List<String> allowedOrigins = new ArrayList<>();
}
