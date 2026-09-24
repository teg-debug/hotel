package com.hotel.booking;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.hotel.common.SearchCacheSupport;
import com.hotel.config.BookingProperties;
import com.hotel.config.MemberProperties;
import com.hotel.config.MybatisPlusConfig;
import com.hotel.config.RedisConfig;
import com.hotel.config.RedissonConfig;
import com.hotel.service.impl.BookingServiceImpl;
import com.hotel.service.impl.HotelSearchServiceImpl;
import com.hotel.service.impl.OrderTxServiceImpl;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 并发用例的最小 Spring 上下文。
 *
 * <p>不启动 {@code HotelApplication}：那会一并拉起 WebSocket、定时任务、AI 对话链等
 * 与本用例无关且需要外部密钥的组件，既慢又容易因为无关原因失败。
 * 这里只装配「下单链路」真正依赖的东西——数据源、MyBatis-Plus、Redis、
 * Redisson 锁客户端，以及两个被测服务，让被测代码与生产实现保持同一份。</p>
 */
@Configuration(proxyBeanMethods = false)
@MapperScan("com.hotel.mapper")
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        RedisAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class,
        JacksonAutoConfiguration.class
})
@Import({
        RedissonConfig.class,
        RedisConfig.class,
        MybatisPlusConfig.class,
        MemberProperties.class,
        BookingProperties.class,
        SearchCacheSupport.class,
        BookingServiceImpl.class,
        OrderTxServiceImpl.class,
        HotelSearchServiceImpl.class
})
public class BookingTestApplication {
}
