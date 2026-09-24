package com.hotel.booking;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.hotel.common.GlobalExceptionHandler;
import com.hotel.common.SearchCacheSupport;
import com.hotel.config.BookingProperties;
import com.hotel.config.CorsProperties;
import com.hotel.config.JacksonConfig;
import com.hotel.config.MemberProperties;
import com.hotel.config.MybatisPlusConfig;
import com.hotel.config.RedisConfig;
import com.hotel.config.RedissonConfig;
import com.hotel.config.WebMvcConfig;
import com.hotel.controller.BookingController;
import com.hotel.controller.HotelSearchController;
import com.hotel.security.JwtInterceptor;
import com.hotel.security.JwtUtil;
import com.hotel.security.TokenCache;
import com.hotel.service.impl.BookingServiceImpl;
import com.hotel.service.impl.HotelSearchServiceImpl;
import com.hotel.service.impl.OrderTxServiceImpl;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 接口层吞吐测量用的最小 Web 上下文：真实 Tomcat + 真实拦截器链 + 真实 JSON 序列化。
 *
 * <p>与 {@link BookingTestApplication} 的区别只在多了一层 Web：后者是
 * {@code WebEnvironment.NONE}，调用的是 Service 方法，测不出 HTTP 栈的成本。
 * 这里补上内嵌 Tomcat、DispatcherServlet、JWT 拦截器与两个控制器，
 * 仍然<b>不</b>引入 AI 对话链、WebSocket、定时任务——它们与本测量无关，
 * 却需要外部密钥，容易让基准因为无关原因起不来。</p>
 *
 * <p>被排除的组件（如管理端、支付、聊天控制器）不会进入测量范围，
 * 因此这里测的是「搜索 + 下单」这两个主链路的接口层吞吐，
 * 不代表整个应用所有接口的吞吐。</p>
 */
@Configuration(proxyBeanMethods = false)
@MapperScan("com.hotel.mapper")
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        RedisAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class,
        JacksonAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        ValidationAutoConfiguration.class,
        ServletWebServerFactoryAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class
})
@Import({
        RedissonConfig.class,
        RedisConfig.class,
        MybatisPlusConfig.class,
        JacksonConfig.class,
        MemberProperties.class,
        BookingProperties.class,
        CorsProperties.class,
        SearchCacheSupport.class,
        BookingServiceImpl.class,
        OrderTxServiceImpl.class,
        HotelSearchServiceImpl.class,
        JwtUtil.class,
        TokenCache.class,
        JwtInterceptor.class,
        WebMvcConfig.class,
        GlobalExceptionHandler.class,
        BookingController.class,
        HotelSearchController.class
})
public class HttpBenchmarkApplication {
}
