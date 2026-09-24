package com.hotel.booking;

import com.hotel.security.UserContext;
import com.hotel.service.OrderPreparation;
import com.hotel.service.OrderTxService;
import com.hotel.service.RoomUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 房间级锁的参数扫描：候选池大小 × 单请求最多尝试候选数 × 每候选抢锁等待。
 *
 * <p>为什么要单独做一个扫描器：三个旋钮相互影响，靠「改一个常量、跑一次完整基准」来扫参，
 * 每档都要重启 Spring 上下文，一次几分钟；这里在同一个 JVM 里用真实生产 bean
 * （{@code OrderTxService.prepare} + {@code createOrder(prep, roomId)} + 真实 Redisson 锁）
 * 复现编排循环，只把三个旋钮参数化，一轮跑完整个网格。冠军配置最后会用生产路径
 * （{@code BookingThroughputBenchmark}）复核，确保不是「扫描器跑得好看」。</p>
 *
 * <p>默认不参与构建（类名不匹配 surefire 默认规则），显式运行：
 * {@code mvn test -Dtest=BookingLockTuningSweep}</p>
 *
 * <p>评价口径：QPS 是产能，成功率是「用户第一次就被拒」的比例。被拒的请求未必没房，
 * 可能只是它那几个候选被别人抢走了（可重试）。因此不看单一指标，看两者的权衡曲线。</p>
 */
@DisplayName("房间级锁参数扫描")
class BookingLockTuningSweep extends BookingIntegrationTestSupport {

    private static final int THREADS = 32;

    private static final int ORDERS_PER_THREAD = 12;

    /** 抢锁等待的候选值（第二轮在冠军配置上单独扫） */
    private static final List<Long> WAIT_CANDIDATES = List.of(25L, 50L, 100L);

    @Autowired
    private OrderTxService orderTxService;

    @Autowired
    private RedissonClient redissonClient;

    @Test
    @DisplayName("扫描候选池 × 尝试次数 × 抢锁等待，并给出权衡曲线")
    void sweep() throws Exception {
        LocalDate checkin = LocalDate.now().plusDays(7);
        LocalDate checkout = checkin.plusDays(2);

        List<Result> results = new ArrayList<>();

        // 第一轮：固定等待 50 ms，扫「候选池 × 尝试次数」
        List<int[]> grid = List.of(
                new int[]{10, 3}, new int[]{10, 5}, new int[]{10, 8},
                new int[]{20, 3}, new int[]{20, 5}, new int[]{20, 8},
                new int[]{50, 3}, new int[]{50, 5}, new int[]{50, 8});
        for (int[] cell : grid) {
            results.add(measure(cell[0], cell[1], 50L, checkin, checkout));
        }

        // 第二轮：在「吞吐最高」的池/次数组合上扫等待时间
        Result best = results.stream().max((left, right) -> Double.compare(left.qps(), right.qps())).orElseThrow();
        for (Long waitMillis : WAIT_CANDIDATES) {
            if (waitMillis == 50L) {
                continue;
            }
            results.add(measure(best.poolSize(), best.attempts(), waitMillis, checkin, checkout));
        }

        printReport(results);
        assertThat(results).allSatisfy(result ->
                assertThat(result.successes()).as("%s 应至少有成功的下单", result.label()).isPositive());
    }

    // ===================== 扫描骨架 =====================

    private Result measure(int poolSize, int attempts, long waitMillis,
                           LocalDate checkin, LocalDate checkout) throws Exception {
        Fixture fixture = createFixture(THREADS * ORDERS_PER_THREAD, "sweep");
        OrderPreparation template = orderTxService.prepare(
                orderRequest(fixture, null, checkin, checkout), fixture.userId());

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Worker>> futures = new ArrayList<>(THREADS);
        try {
            for (int threadIndex = 0; threadIndex < THREADS; threadIndex++) {
                futures.add(executor.submit(() -> {
                    Worker worker = new Worker();
                    UserContext.set(fixture.userId(), "sweep", 0);
                    try {
                        start.await(30, TimeUnit.SECONDS);
                        for (int i = 0; i < ORDERS_PER_THREAD; i++) {
                            bookOnce(template, poolSize, attempts, waitMillis, checkin, checkout, worker);
                        }
                    } finally {
                        UserContext.clear();
                    }
                    return worker;
                }));
            }
            start.countDown();
            List<Worker> workers = new ArrayList<>(THREADS);
            long startedAt = System.nanoTime();
            for (Future<Worker> future : futures) {
                workers.add(future.get(300, TimeUnit.SECONDS));
            }
            long elapsedNanos = System.nanoTime() - startedAt;

            int successes = 0, rejections = 0, lockAttempts = 0, transactionAttempts = 0;
            List<Long> latencies = new ArrayList<>();
            for (Worker worker : workers) {
                successes += worker.successes;
                rejections += worker.rejections;
                lockAttempts += worker.lockAttempts;
                transactionAttempts += worker.transactionAttempts;
                latencies.addAll(worker.latencies);
            }
            return new Result(poolSize, attempts, waitMillis, THREADS * ORDERS_PER_THREAD,
                    successes, rejections, lockAttempts, transactionAttempts, latencies, elapsedNanos);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 一次下单尝试；与生产编排同构，差异只有三个可调旋钮 */
    private void bookOnce(OrderPreparation template, int poolSize, int attempts, long waitMillis,
                          LocalDate checkin, LocalDate checkout, Worker worker) {
        long startedAt = System.nanoTime();
        try {
            List<Long> pool = new ArrayList<>(roomMapper.selectAvailableRoomIds(
                    template.hotel().getId(), template.roomType().getId(), checkin, checkout, poolSize));
            if (pool.isEmpty()) {
                worker.rejections++;
                return;
            }
            Collections.shuffle(pool);
            OrderPreparation preparation = new OrderPreparation(template.userId(), template.hotel(),
                    template.roomType(), template.nights(), template.unitPrice(), template.discount(),
                    template.totalAmount(), template.checkinDate(), template.checkoutDate(),
                    template.guestName(), template.guestPhone(), template.requestedRoomId(), pool);

            int maxAttempts = Math.min(pool.size(), attempts);
            for (int i = 0; i < maxAttempts; i++) {
                Long roomId = pool.get(i);
                worker.lockAttempts++;
                RLock lock = redissonClient.getLock("lock:room:" + roomId);
                boolean locked = false;
                try {
                    locked = lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
                    if (!locked) {
                        continue;
                    }
                    worker.transactionAttempts++;
                    orderTxService.createOrder(preparation, roomId);
                    worker.successes++;
                    return;
                } catch (RoomUnavailableException e) {
                    // 该候选已被抢走 → 换下一个
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } finally {
                    if (locked && lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            }
            worker.rejections++;
        } finally {
            worker.latencies.add(System.nanoTime() - startedAt);
        }
    }

    private static final class Worker {
        private int successes;
        private int rejections;
        private int lockAttempts;
        private int transactionAttempts;
        private final List<Long> latencies = new ArrayList<>();
    }

    private record Result(int poolSize, int attempts, long waitMillis, int requests,
                          int successes, int rejections, int lockAttempts, int transactionAttempts,
                          List<Long> latencies, long elapsedNanos) {

        String label() {
            return "池" + poolSize + "/尝试" + attempts + "/等待" + waitMillis + "ms";
        }

        int rejected() {
            return rejections;
        }

        double successRate() {
            return successes * 100.0 / requests;
        }

        double qps() {
            return successes / (elapsedNanos / 1_000_000_000.0);
        }

        double elapsedMillis() {
            return elapsedNanos / 1_000_000.0;
        }

        double averageLatencyMillis() {
            return latencies.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        }

        double averageLockAttempts() {
            return lockAttempts / (double) requests;
        }

        double averageTransactionAttempts() {
            return transactionAttempts / (double) requests;
        }

        double percentileMillis(double percentile) {
            long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
            int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, index))] / 1e6;
        }
    }

    private void printReport(List<Result> results) {
        StringBuilder report = new StringBuilder("\n===== 房间级锁参数扫描（" + THREADS + " 线程 × "
                + ORDERS_PER_THREAD + " 单）=====\n");
        report.append("基础设施 : ").append(BookingTestInfrastructure.description()).append('\n');
        report.append("说明     : 每格都是独立夹具，字段含义见类注释；被拒 = 候选尝试完仍没抢到房\n\n");
        report.append(String.format(Locale.ROOT,
                "%-20s %8s %8s %9s %8s %8s %8s %9s %11s %11s%n",
                "配置", "QPS", "成功率%", "被拒", "P50(ms)", "P95(ms)", "P99(ms)", "耗时(ms)",
                "锁尝试/请求", "事务尝试/请求"));
        for (Result result : results) {
            report.append(String.format(Locale.ROOT,
                    "%-20s %8.1f %8.1f %9d %8.1f %8.1f %8.1f %9.0f %11.2f %11.2f%n",
                    result.label(), result.qps(), result.successRate(), result.rejected(),
                    result.percentileMillis(50), result.percentileMillis(95), result.percentileMillis(99),
                    result.elapsedMillis(), result.averageLockAttempts(), result.averageTransactionAttempts()));
        }
        report.append("\n按 QPS 排序：\n");
        results.stream()
                .sorted((left, right) -> Double.compare(right.qps(), left.qps()))
                .forEach(result -> report.append(String.format(Locale.ROOT,
                        "  %-20s QPS %6.1f  成功率 %5.1f%%  平均延迟 %6.1f ms%n",
                        result.label(), result.qps(), result.successRate(), result.averageLatencyMillis())));
        report.append("================================\n");
        System.out.println(report);
        write(report.toString());
    }

    private void write(String content) {
        try {
            Path path = Path.of("target", "benchmark", "lock-tuning-sweep.txt");
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("扫参报告落盘失败：" + e.getMessage());
        }
    }
}
