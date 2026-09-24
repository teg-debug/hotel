package com.hotel.config;

import com.hotel.security.JwtInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web MVC 配置：注册 JWT 拦截器、跨域、BCrypt（Jackson 定制器见 JacksonConfig）
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final CorsProperties corsProperties;

    /** 注册 JWT 拦截器：除登录/注册/支付回调外，/api/v1/** 均需鉴权 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(
                        "/api/v1/auth/login",
                        "/api/v1/auth/register",
                        // 支付回调由支付网关调用，无用户态；其安全性由验签、时间窗、
                        // 随机串防重放与来源 IP 白名单共同保证
                        "/api/v1/payment/callback");
    }

    /**
     * 跨域：仅在配置了来源白名单时开启。
     *
     * <p>原先使用通配来源并允许携带凭据，等价于对任意站点开放带登录态的接口；
     * 前端开发环境经 Vite 代理访问属同源，因此留空即可正常工作。</p>
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = corsProperties.getAllowedOrigins();
        if (origins == null || origins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/v1/**")
                .allowedOriginPatterns(origins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
