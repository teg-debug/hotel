package com.hotel.task;

import com.hotel.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 会话超时回收定时任务。
 *
 * <p>客服会话原先只有「超时回收」这个状态常量，没有任何写入点，结果是无人响应的会话
 * 永远停留在进行中或已转人工，持续占用坐席的待办列表，统计口径也把它们算作未解决。</p>
 *
 * <p>用分布式调度锁保证多实例部署时同一批会话只会被一个实例处理。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSessionTimeoutTask {

    private static final String SCHEDULE_LOCK_KEY = "lock:task:chat-session-timeout";
    private static final long LOCK_WAIT_SECONDS = 0;

    private final ChatService chatService;
    private final RedissonClient redissonClient;

    /** 闲置多久后回收（分钟） */
    @Value("${app.chat.session-timeout-minutes:30}")
    private int sessionTimeoutMinutes;

    /** 单次回收上限，避免一次处理过多导致任务长时间占用 */
    @Value("${app.chat.reclaim-batch-size:500}")
    private int reclaimBatchSize;

    /** 每 5 分钟执行一次（fixedDelay：上次执行完成后间隔 5 分钟） */
    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    public void reclaimIdleSessions() {
        RLock lock = redissonClient.getLock(SCHEDULE_LOCK_KEY);
        boolean locked = false;
        try {
            // waitTime=0：抢不到锁说明其他实例正在处理，本次直接跳过
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(Math.max(1, sessionTimeoutMinutes));
            int reclaimed = chatService.reclaimIdleSessions(cutoff, Math.max(1, reclaimBatchSize));
            if (reclaimed > 0) {
                log.info("回收超时会话 {} 个，闲置阈值 {} 分钟", reclaimed, sessionTimeoutMinutes);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("会话超时回收任务执行失败", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
