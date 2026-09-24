package com.hotel.booking;

import com.hotel.service.OrderPreparation;
import com.hotel.service.OrderTxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单线程延迟拆解：把「一次下单」的耗时拆成锁外准备、锁内事务、锁自身开销三块。
 *
 * <p>用途：定位热点房型吞吐上限到底卡在哪一环。热点吞吐上限 ≈ {@code 1 / (锁内事务 + 锁交接开销)}，
 * 先知道各环各占多少，才能判断该缩短临界区（方案 A）还是该拆锁提高并行度（方案 B）。</p>
 *
 * <p>默认不参与构建（类名不匹配 surefire 默认规则），显式运行：
 * {@code mvn test -Dtest=BookingLatencyBreakdown}</p>
 */
@DisplayName("下单延迟拆解（单线程）")
class BookingLatencyBreakdown extends BookingIntegrationTestSupport {

    private static final int ITERATIONS = 60;

    private static final int WARMUP = 10;

    @Autowired
    private OrderTxService orderTxService;

    @Autowired
    private RedissonClient redissonClient;

    @Test
    @DisplayName("拆解锁外准备、锁内事务与锁自身开销")
    void breakdown() {
        LocalDate checkin = LocalDate.now().plusDays(9);
        LocalDate checkout = checkin.plusDays(2);

        // 1. 锁外只读准备（校验酒店/房型 + 算价）：方案 A 把它移出了临界区，这就是被移走的量
        Fixture prepareFixture = createFixture(1, "breakdown-prepare");
        report("锁外准备 prepare", sample(() ->
                orderTxService.prepare(orderRequest(prepareFixture, null, checkin, checkout),
                        prepareFixture.userId())));

        // 2. 锁内事务：指定房间 + 复核 + 落单 + 加锁读复核（不含 Redis 锁）
        Fixture txFixture = createFixture(ITERATIONS + WARMUP + 5, "breakdown-tx");
        OrderPreparation preparation = orderTxService.prepare(
                orderRequest(txFixture, null, checkin, checkout), txFixture.userId());
        AtomicInteger txCursor = new AtomicInteger();
        report("锁内事务 createOrder", sample(() ->
                orderTxService.createOrder(preparation, txFixture.roomIds().get(txCursor.getAndIncrement()))));

        // 3. 完整链路：锁外准备 + Redis 锁 + 锁内事务
        Fixture fullFixture = createFixture(ITERATIONS + WARMUP + 5, "breakdown-full");
        report("完整链路 bookingService", sample(() ->
                createOrderAs(fullFixture.userId(), orderRequest(fullFixture, null, checkin, checkout))));

        // 4. Redis 锁自身开销：无竞争下的「加锁 + 释放」往返
        RLock probe = redissonClient.getLock("lock:probe:" + System.nanoTime());
        report("Redis 锁 加锁+解锁", sample(() -> {
            try {
                if (probe.tryLock(3, TimeUnit.SECONDS)) {
                    probe.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    private List<Long> sample(Runnable action) {
        List<Long> samples = new ArrayList<>(ITERATIONS);
        for (int i = 0; i < WARMUP; i++) {
            action.run();
        }
        for (int i = 0; i < ITERATIONS; i++) {
            long startedAt = System.nanoTime();
            action.run();
            samples.add(System.nanoTime() - startedAt);
        }
        return samples;
    }

    private void report(String name, List<Long> samples) {
        samples.sort(Long::compare);
        double average = samples.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        double p50 = samples.get(samples.size() / 2) / 1e6;
        double p95 = samples.get((int) Math.min(samples.size() - 1, Math.ceil(samples.size() * 0.95) - 1)) / 1e6;
        System.out.println(String.format(Locale.ROOT,
                "%-26s 平均 %6.2f ms  P50 %6.2f ms  P95 %6.2f ms  (n=%d)",
                name, average, p50, p95, samples.size()));
    }
}
