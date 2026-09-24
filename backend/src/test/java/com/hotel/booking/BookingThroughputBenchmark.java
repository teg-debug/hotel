package com.hotel.booking;

import com.hotel.common.BusinessException;
import com.hotel.dto.CreateOrderDTO;
import com.hotel.security.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下单链路吞吐量（QPS）测量。
 *
 * <p>与同包下并发用例的分工：并发用例断言「正确性」（零超订），本类只测「吞吐」。
 * 两者口径不同——32 个线程抢同一间房测出的是正确性，不能拿它的耗时反推 QPS。</p>
 *
 * <p>默认不参与构建：类名不匹配 surefire 的默认扫描规则（{@code *Test}），
 * 需要显式指定才会运行：{@code mvn test -Dtest=BookingThroughputBenchmark}。</p>
 *
 * <p>测量两个场景：</p>
 * <ul>
 *   <li>散列：各线程的下单落在不同房间，互不争锁，反映链路本身处理能力；</li>
 *   <li>热点：所有线程抢同一房型的同一把 Redisson 锁，反映单房型被串行化后的吞吐上限。
 *       该场景下部分请求会因抢锁超时被拒（服务层的「当前预订人数较多」），
 *       因此分别报告成功数、拒绝数与成功率。</li>
 * </ul>
 *
 * <p>口径说明：QPS = 成功下单数 / 测量墙钟时间，延迟统计只含成功请求；
 * 结果依赖本机 MySQL/Redis 与硬件，仅用于纵向对比（改前改后），不能当作生产容量。</p>
 */
@DisplayName("下单链路吞吐测量（QPS）")
class BookingThroughputBenchmark extends BookingIntegrationTestSupport {

    private static final int[] CONCURRENCY_LEVELS = {8, 16, 32};

    private static final int ORDERS_PER_THREAD = 12;

    /**
     * 热点场景下被拒请求的合法提示：候选房间被抢光（库存不足）或没抢到房间级锁。
     * 房间级锁 + 候选重试下，「库存不足」才是主流拒绝原因。
     */
    private static final Set<String> EXPECTED_HOT_REJECTIONS =
            Set.of(MSG_LOCK_BUSY, MSG_SYSTEM_BUSY, MSG_STOCK_NOT_ENOUGH);

    @Test
    @DisplayName("测量散列、热点与小库存热点在不同并发度下的 QPS 与延迟分位")
    void measureBookingThroughput() throws Exception {
        LocalDate checkin = LocalDate.now().plusDays(7);
        LocalDate checkout = checkin.plusDays(2);

        List<Measurement> measurements = new ArrayList<>();
        for (int threads : CONCURRENCY_LEVELS) {
            measurements.add(spread(threads, checkin, checkout));
        }
        for (int threads : CONCURRENCY_LEVELS) {
            measurements.add(hotRoomType(threads, checkin, checkout));
        }
        for (int threads : CONCURRENCY_LEVELS) {
            measurements.add(smallInventoryHotRoomType(threads, checkin));
        }
        printReport(measurements);

        assertThat(measurements).allSatisfy(measurement ->
                assertThat(measurement.successes()).as("%s：应至少有成功的下单", measurement.name()).isPositive());
        measurements.stream()
                .filter(measurement -> measurement.name().startsWith("散列"))
                .forEach(measurement -> assertThat(measurement.failures())
                        .as("%s：互不争锁时不应有请求被拒", measurement.name())
                        .isZero());
        measurements.stream()
                .filter(measurement -> measurement.name().startsWith("热点"))
                .forEach(measurement -> assertThat(measurement.rejectionMessages())
                        .as("%s：被拒请求只能是库存不足或抢锁超时类业务提示", measurement.name())
                        .allSatisfy(message -> assertThat(EXPECTED_HOT_REJECTIONS).contains(message)));
    }

    // ===================== 三种场景 =====================

    /**
     * 散列：每个线程用自己独立的酒店 + 房型（也就是各自独立的锁键），互不争锁。
     * 注意「各自订不同房间」并不等于无竞争——同一房型的锁键是相同的，仍会串行化。
     */
    private Measurement spread(int threads, LocalDate checkin, LocalDate checkout) throws Exception {
        List<Fixture> fixtures = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            fixtures.add(createFixture(ORDERS_PER_THREAD, "bench-spread"));
        }
        return run("散列(各订各房)", threads, ORDERS_PER_THREAD, (threadIndex, iteration) -> {
            Fixture fixture = fixtures.get(threadIndex);
            Long roomId = fixture.roomIds().get(iteration);
            return createOrderAs(fixture.userId(), orderRequest(fixture, roomId, checkin, checkout));
        });
    }

    /** 热点：全部线程使用同一房型（同一把锁），由系统自动分配房间 */
    private Measurement hotRoomType(int threads, LocalDate checkin, LocalDate checkout) throws Exception {
        Fixture fixture = createFixture(threads * ORDERS_PER_THREAD, "bench-hot");
        Long userId = fixture.userId();
        return run("热点(同房型争锁)", threads, ORDERS_PER_THREAD, (threadIndex, iteration) ->
                createOrderAs(userId, orderRequest(fixture, null, checkin, checkout)));
    }

    /**
     * 小库存热点：单房型只有 6 间房，接近生产里常见的房量。
     *
     * <p>关键设计：每个请求用互不重叠的入住区间，因此不会把库存吃光——
     * 测的是「6 间房能并行扛多少单」，而不是「6 间房被抢完的速度」。
     * 若用同一区间，384 个请求里只有 6 个能成功，成功率会被库存本身钉死，
     * 量出来的是库存上限而不是锁的并行度。房型级锁下这个场景同样是 ~42 QPS
     * （锁把房型内所有下单串行化，与房量无关），因此这里能直接看出锁粒度带来的差别。</p>
     */
    private Measurement smallInventoryHotRoomType(int threads, LocalDate base) throws Exception {
        int inventory = 6;
        Fixture fixture = createFixture(inventory, "bench-hot-small");
        Long userId = fixture.userId();
        return run("热点6间(错峰区间)", threads, ORDERS_PER_THREAD, (threadIndex, iteration) -> {
            LocalDate checkin = base.plusDays((long) threadIndex * ORDERS_PER_THREAD + iteration);
            return createOrderAs(userId, orderRequest(fixture, null, checkin, checkin.plusDays(1)));
        });
    }

    // ===================== 测量骨架 =====================

    @FunctionalInterface
    private interface OrderJob {
        Object invoke(int threadIndex, int iteration);
    }

    private Measurement run(String name, int threads, int ordersPerThread, OrderJob job) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WorkerResult>> futures = new ArrayList<>(threads);
        try {
            for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
                final int index = threadIndex;
                futures.add(executor.submit(() -> {
                    UserContext.set(0L, "benchmark", 0);
                    try {
                        start.await(30, TimeUnit.SECONDS);
                        long[] latencies = new long[ordersPerThread];
                        List<String> rejections = new ArrayList<>();
                        int successes = 0;
                        for (int iteration = 0; iteration < ordersPerThread; iteration++) {
                            long startedAt = System.nanoTime();
                            try {
                                job.invoke(index, iteration);
                                successes++;
                                latencies[iteration] = System.nanoTime() - startedAt;
                            } catch (BusinessException e) {
                                latencies[iteration] = System.nanoTime() - startedAt;
                                rejections.add(e.getMessage());
                            }
                        }
                        return new WorkerResult(successes, rejections, latencies);
                    } finally {
                        UserContext.clear();
                    }
                }));
            }
            long startedAt = System.nanoTime();
            start.countDown();
            int successes = 0;
            List<String> rejections = new ArrayList<>();
            List<Long> latencies = new ArrayList<>();
            for (Future<WorkerResult> future : futures) {
                WorkerResult result = future.get(300, TimeUnit.SECONDS);
                successes += result.successes();
                rejections.addAll(result.rejections());
                Arrays.stream(result.latencies()).forEach(latencies::add);
            }
            long elapsedNanos = System.nanoTime() - startedAt;
            return new Measurement(name, threads, successes, rejections, latencies, elapsedNanos);
        } finally {
            executor.shutdownNow();
        }
    }

    private record WorkerResult(int successes, List<String> rejections, long[] latencies) {
    }

    private record Measurement(String name, int threads, int successes, List<String> rejections,
                               List<Long> latenciesNanos, long elapsedNanos) {

        int total() {
            return successes + rejections.size();
        }

        int failures() {
            return rejections.size();
        }

        double successRate() {
            return total() == 0 ? 0 : successes * 100.0 / total();
        }

        double qps() {
            return successes / (elapsedNanos / 1_000_000_000.0);
        }

        double elapsedMillis() {
            return elapsedNanos / 1_000_000.0;
        }

        double percentileMillis(double percentile) {
            if (latenciesNanos.isEmpty()) {
                return 0;
            }
            long[] sorted = latenciesNanos.stream().mapToLong(Long::longValue).sorted().toArray();
            int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, index))] / 1_000_000.0;
        }

        double avgMillis() {
            return latenciesNanos.isEmpty()
                    ? 0
                    : latenciesNanos.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
        }

        List<String> rejectionMessages() {
            return rejections;
        }
    }

    private void printReport(List<Measurement> measurements) {
        StringBuilder report = new StringBuilder("\n===== 下单链路吞吐测量（QPS）=====\n");
        report.append("基础设施 : ").append(BookingTestInfrastructure.description()).append('\n');
        report.append("并发设置 : ").append(Arrays.toString(CONCURRENCY_LEVELS))
                .append(" 线程，每线程 ").append(ORDERS_PER_THREAD).append(" 单（日期区间各场景不同）\n\n");
        report.append(String.format("%-18s %6s %8s %8s %8s %10s %9s %9s %9s %9s%n",
                "场景", "线程", "总请求", "成功", "被拒", "成功率%", "QPS", "P50(ms)", "P95(ms)", "P99(ms)"));
        for (Measurement measurement : measurements) {
            report.append(String.format("%-18s %6d %8d %8d %8d %10.1f %9.1f %9.1f %9.1f %9.1f%n",
                    measurement.name(), measurement.threads(), measurement.total(), measurement.successes(),
                    measurement.failures(), measurement.successRate(), measurement.qps(),
                    measurement.percentileMillis(50), measurement.percentileMillis(95),
                    measurement.percentileMillis(99)));
        }
        report.append('\n');
        for (Measurement measurement : measurements) {
            report.append(String.format("%-18s 耗时 %.0f ms，平均延迟 %.1f ms，被拒提示=%s%n",
                    measurement.name(), measurement.elapsedMillis(), measurement.avgMillis(),
                    measurement.rejectionMessages().isEmpty()
                            ? "无" : measurement.rejectionMessages().stream().distinct().toList()));
        }
        report.append("================================\n");
        System.out.println(report);
        writeReportFile(report.toString());
    }

    /** 同时落盘一份 UTF-8 报告，避免控制台编码把中文和数字混在一起看不清 */
    private void writeReportFile(String content) {
        try {
            Path reportPath = Path.of("target", "benchmark", "booking-throughput-report.txt");
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("吞吐报告落盘失败：" + e.getMessage());
        }
    }
}
