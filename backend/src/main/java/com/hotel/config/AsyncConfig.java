package com.hotel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步执行配置。
 */
@Configuration
public class AsyncConfig {

    /** 流式推送并发上限：每个流式回答会占用一个线程等待模型输出 */
    private static final int CHAT_STREAM_THREADS = 8;

    /**
     * 客服流式推送线程池。
     *
     * <p>流式回答需要在线程上等待模型逐字输出，如果占用 Web 容器线程，
     * 少量并发的长回答就会把请求线程耗尽、拖慢其它接口。</p>
     *
     * <p>用 {@code destroyMethod = "shutdown"} 让容器关闭时回收线程。</p>
     */
    @Bean(name = "chatStreamExecutor", destroyMethod = "shutdown")
    public ExecutorService chatStreamExecutor() {
        AtomicInteger seq = new AtomicInteger();
        return Executors.newFixedThreadPool(CHAT_STREAM_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "chat-sse-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }
}
