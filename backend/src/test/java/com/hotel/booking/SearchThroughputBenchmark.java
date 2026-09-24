package com.hotel.booking;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.common.BusinessException;
import com.hotel.common.SearchCacheSupport;
import com.hotel.dto.HotelSearchDTO;
import com.hotel.entity.Hotel;
import com.hotel.entity.Room;
import com.hotel.entity.RoomType;
import com.hotel.entity.User;
import com.hotel.service.HotelSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 搜索链路吞吐测量（QPS）。
 *
 * <p>与下单测量的区别：搜索是读路径，没有分布式锁，但有一个 Redis 5 分钟缓存
 * （键 = {@code hotel:search:v版本号:入参摘要}），因此「搜索 QPS」必须分口径说，
 * 否则同一个系统能测出相差一个数量级的数字：</p>
 *
 * <ul>
 *   <li>缓存命中：只走一次 Redis 往返 + 反序列化；</li>
 *   <li>缓存未命中：多条件分页 + 逐酒店查房型 + 逐房型统计可售数（典型 N+1），再回填缓存；</li>
 *   <li>被失效扰动：版本号被取消/退款/退房自增后，原本命中的键整体作废，重新回到未命中路径。
 *       注意<b>下单本身不失效搜索缓存</b>，只有取消、退款、退房会——所以「搜索 + 下单」混跑
 *       并不构成对搜索缓存的压力。</li>
 * </ul>
 *
 * <p>两个方法学处理，避免测出好看但不可比的数字：</p>
 *
 * <ol>
 *   <li>先做预热：JIT 与 MySQL 缓冲池冷启动会让第一个档位明显偏慢，
 *       不预热就会出现「并发越高反而越快」的假象；</li>
 *   <li>失效扰动用固定时长窗口（3 秒）而不是固定请求数：请求只要 100 ms 就跑完，
 *       1~10 次/秒的失效在这么短的窗口里根本不会发生，等于什么都没测。</li>
 * </ol>
 *
 * <p>默认不参与构建（类名不匹配 surefire 默认规则），显式运行：
 * {@code mvn test -Dtest=SearchThroughputBenchmark}</p>
 */
@DisplayName("搜索链路吞吐测量（QPS）")
class SearchThroughputBenchmark extends BookingIntegrationTestSupport {

    private static final int[] CONCURRENCY_LEVELS = {8, 16, 32};

    private static final int PAGE_SIZE = 10;

    private static final int WARMUP_REQUESTS = 120;

    /** 每个档位的稳态观察窗口；跑两轮、只取第二轮（第一轮用来消化线程爬升与连接预热） */
    private static final long WINDOW_MILLIS = 1500;

    private static final int ROUNDS_PER_LEVEL = 2;

    /** 失效扰动的观察窗口与失效频率（次/秒），0 表示不失效（对照） */
    private static final long CHURN_WINDOW_MILLIS = 3000;

    private static final int[] INVALIDATIONS_PER_SECOND = {0, 100, 1000};

    /** 用专属城市隔离测量范围：只有本用例造的酒店会被搜到，页面内容确定 */
    private static final String SEARCH_CITY = "搜索测速城";

    private static final String CACHE_KEY_PATTERN = "hotel:search:*";

    @Autowired
    private HotelSearchService hotelSearchService;

    @Autowired
    private SearchCacheSupport searchCacheSupport;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    @DisplayName("测量搜索命中/未命中/失效扰动与选房查询的 QPS 与延迟分位")
    void measureSearchThroughput() throws Exception {
        // 10 家酒店 × 2 个房型 × 2 间房：一页正好放满，未命中路径的 N+1 查询量与生产同量级
        List<Long> hotelIds = seedHotels(10, 2, 2);
        Long sampleHotelId = hotelIds.get(0);
        Long sampleRoomTypeId = firstRoomTypeId(sampleHotelId);
        LocalDate base = LocalDate.now().plusDays(3);
        HotelSearchDTO template = searchRequest(base);
        LocalDate sampleCheckin = base;
        LocalDate sampleCheckout = base.plusDays(2);

        warmUp(template, base, sampleHotelId, sampleRoomTypeId, sampleCheckin, sampleCheckout);
        clearSearchCache();

        List<Measurement> measurements = new ArrayList<>();
        for (int threads : CONCURRENCY_LEVELS) {
            measurements.add(searchCacheHit(threads, template));
        }
        for (int threads : CONCURRENCY_LEVELS) {
            measurements.add(searchCacheMiss(threads, template, base));
        }
        for (int threads : CONCURRENCY_LEVELS) {
            measurements.add(availableRoomsQuery(threads, sampleHotelId, sampleRoomTypeId,
                    sampleCheckin, sampleCheckout));
        }
        for (int invalidationsPerSecond : INVALIDATIONS_PER_SECOND) {
            measurements.add(searchUnderInvalidation(CONCURRENCY_LEVELS[CONCURRENCY_LEVELS.length - 1],
                    template, invalidationsPerSecond));
        }
        printReport(measurements);

        assertThat(measurements).allSatisfy(measurement ->
                assertThat(measurement.successes()).as("%s：应至少有成功的请求", measurement.name()).isPositive());
        measurements.stream()
                .filter(measurement -> measurement.name().startsWith("搜索"))
                .forEach(measurement -> assertThat(measurement.rejections())
                        .as("%s：搜索是读路径，不应有业务异常", measurement.name())
                        .isEmpty());
    }

    // ===================== 预热 =====================

    /** 先跑一批混合请求，把 JIT 与缓冲池热起来；结果不计入报告 */
    private void warmUp(HotelSearchDTO template, LocalDate base, Long hotelId, Long roomTypeId,
                        LocalDate checkin, LocalDate checkout) {
        for (int i = 0; i < WARMUP_REQUESTS; i++) {
            hotelSearchService.search(template);
            HotelSearchDTO cold = copyOf(template);
            cold.setCheckin(base.plusDays(1000L + i));
            cold.setCheckout(base.plusDays(1001L + i));
            hotelSearchService.search(cold);
            hotelSearchService.availableRooms(hotelId, roomTypeId, checkin, checkout);
        }
    }

    /** 清掉本用例自己的缓存键族并推进版本号，让计数证据从干净基线开始 */
    private void clearSearchCache() {
        Set<String> keys = redisTemplate.keys(CACHE_KEY_PATTERN);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        searchCacheSupport.invalidate();
    }

    // ===================== 四组场景 =====================

    /** 缓存命中：所有请求用同一份查询条件（同一个缓存键），预热一次后应当全程命中 */
    private Measurement searchCacheHit(int threads, HotelSearchDTO template) throws Exception {
        hotelSearchService.search(template);
        int keysBefore = searchCacheKeys();
        Measurement measurement = steady("搜索·缓存命中", threads, (threadIndex, iteration) ->
                hotelSearchService.search(template));
        return measurement.withCacheKeys(searchCacheKeys() - keysBefore);
    }

    /** 缓存未命中：每次请求用不同的入住日（全局递增）→ 缓存键各不相同 → 必然走数据库路径 */
    private Measurement searchCacheMiss(int threads, HotelSearchDTO template, LocalDate base) throws Exception {
        int keysBefore = searchCacheKeys();
        AtomicLong offset = new AtomicLong();
        Measurement measurement = steady("搜索·缓存未命中", threads, (threadIndex, iteration) -> {
            LocalDate checkin = base.plusDays(offset.getAndIncrement());
            HotelSearchDTO dto = copyOf(template);
            dto.setCheckin(checkin);
            dto.setCheckout(checkin.plusDays(1));
            return hotelSearchService.search(dto);
        });
        return measurement.withCacheKeys(searchCacheKeys() - keysBefore);
    }

    /** 选房页的可用房间查询：无缓存，每次 3 条 SQL */
    private Measurement availableRoomsQuery(int threads, Long hotelId, Long roomTypeId,
                                            LocalDate checkin, LocalDate checkout) throws Exception {
        return steady("选房·可用房间查询", threads, (threadIndex, iteration) ->
                hotelSearchService.availableRooms(hotelId, roomTypeId, checkin, checkout));
    }

    /**
     * 失效扰动：固定 3 秒窗口内持续搜索，同时按指定频率让缓存版本号自增
     * （等价于「取消/退款/退房」的发生频率），观察搜索吞吐与长尾怎么变。
     *
     * <p>任何一次失效都会让全部搜索键作废，正在并发执行的一批请求会同时发现缓存缺失，
     * 于是同时落到数据库上——这正是缓存击穿的形态，也是这个场景要暴露的东西。</p>
     */
    private Measurement searchUnderInvalidation(int threads, HotelSearchDTO template, int perSecond)
            throws Exception {
        hotelSearchService.search(template);
        int keysBefore = searchCacheKeys();
        AtomicBoolean running = new AtomicBoolean(true);
        Thread churn = null;
        if (perSecond > 0) {
            churn = new Thread(() -> {
                long intervalMillis = Math.max(1, 1000L / perSecond);
                while (running.get()) {
                    searchCacheSupport.invalidate();
                    try {
                        Thread.sleep(intervalMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "cache-invalidation-churn");
            churn.setDaemon(true);
            churn.start();
        }
        try {
            Measurement measurement = runFor("搜索·失效扰动(" + perSecond + "次/秒)", threads,
                    CHURN_WINDOW_MILLIS, (threadIndex, iteration) -> hotelSearchService.search(template));
            return measurement.withCacheKeys(searchCacheKeys() - keysBefore);
        } finally {
            running.set(false);
            if (churn != null) {
                churn.interrupt();
                churn.join(TimeUnit.SECONDS.toMillis(5));
            }
        }
    }

    // ===================== 夹具与参数 =====================

    /** 造一批同城酒店：每店 roomTypesPerHotel 个房型、每房型 roomsPerRoomType 间空闲房 */
    private List<Long> seedHotels(int hotelCount, int roomTypesPerHotel, int roomsPerRoomType) {
        String unique = "search-" + System.nanoTime();
        User owner = new User();
        owner.setUsername("search-owner-" + unique);
        owner.setPassword("$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG");
        owner.setNickname("搜索测速经营者");
        owner.setMemberLevel(0);
        owner.setRole(1);
        owner.setStatus(1);
        userMapper.insert(owner);

        List<Long> hotelIds = new ArrayList<>(hotelCount);
        for (int h = 0; h < hotelCount; h++) {
            Hotel hotel = new Hotel();
            hotel.setOwnerId(owner.getId());
            hotel.setName("搜索测速酒店-" + h + "-" + unique);
            hotel.setCity(SEARCH_CITY);
            hotel.setAddress("搜索测速路 " + h + " 号");
            hotel.setStarLevel(3 + (h % 3));
            hotel.setStatus(1);
            hotelMapper.insert(hotel);

            for (int t = 0; t < roomTypesPerHotel; t++) {
                RoomType roomType = new RoomType();
                roomType.setHotelId(hotel.getId());
                roomType.setName("搜索测速房型" + t);
                roomType.setBedType("大床");
                roomType.setArea(30);
                roomType.setMaxGuests(2);
                roomType.setPrice(new BigDecimal(300 + t * 100L));
                roomType.setBreakfast(0);
                roomType.setStatus(1);
                roomTypeMapper.insert(roomType);

                for (int r = 0; r < roomsPerRoomType; r++) {
                    Room room = new Room();
                    room.setHotelId(hotel.getId());
                    room.setRoomTypeId(roomType.getId());
                    room.setRoomNo("S" + t + (100 + r));
                    room.setFloor(1);
                    room.setStatus(Room.STATUS_IDLE);
                    roomMapper.insert(room);
                }
            }
            hotelIds.add(hotel.getId());
        }
        return hotelIds;
    }

    private Long firstRoomTypeId(Long hotelId) {
        return roomTypeMapper.selectList(new LambdaQueryWrapper<RoomType>().eq(RoomType::getHotelId, hotelId))
                .stream().findFirst().orElseThrow().getId();
    }

    private HotelSearchDTO searchRequest(LocalDate checkin) {
        HotelSearchDTO dto = new HotelSearchDTO();
        dto.setCity(SEARCH_CITY);
        dto.setPage(1);
        dto.setSize(PAGE_SIZE);
        dto.setCheckin(checkin);
        dto.setCheckout(checkin.plusDays(2));
        return dto;
    }

    private HotelSearchDTO copyOf(HotelSearchDTO source) {
        HotelSearchDTO dto = new HotelSearchDTO();
        dto.setCity(source.getCity());
        dto.setStarLevel(source.getStarLevel());
        dto.setKeyword(source.getKeyword());
        dto.setCheckin(source.getCheckin());
        dto.setCheckout(source.getCheckout());
        dto.setPage(source.getPage());
        dto.setSize(source.getSize());
        return dto;
    }

    /** Redis 里实际存在的搜索缓存键数量：用来证明命中/未命中两组确实走了不同的路径 */
    private int searchCacheKeys() {
        Set<String> keys = redisTemplate.keys(CACHE_KEY_PATTERN);
        return keys == null ? 0 : keys.size();
    }

    // ===================== 测量骨架 =====================

    @FunctionalInterface
    private interface SearchJob {
        Object invoke(int threadIndex, int iteration);
    }

    /**
     * 稳态测量：跑 {@link #ROUNDS_PER_LEVEL} 轮固定时长窗口，只取最后一轮。
     *
     * <p>为什么不用「每线程固定次数」的突发口径：突发窗口里线程爬升、连接池建连、
     * 缓存写入等一次性成本会占很大比重，同一档位能测出 4 倍差距；
     * 稳态窗口更接近持续流量，各场景之间也才可比。</p>
     */
    private Measurement steady(String name, int threads, SearchJob job) throws Exception {
        Measurement last = null;
        for (int round = 0; round < ROUNDS_PER_LEVEL; round++) {
            last = runFor(name, threads, WINDOW_MILLIS, job);
        }
        return last;
    }

    /** 固定时长：所有线程在 durationMillis 窗口内持续打请求，直到窗口结束 */
    private Measurement runFor(String name, int threads, long durationMillis, SearchJob job) throws Exception {
        return execute(name, threads, index -> {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMillis);
            List<Long> latencies = new ArrayList<>();
            List<String> rejections = new ArrayList<>();
            int successes = 0;
            int iteration = 0;
            while (System.nanoTime() < deadline) {
                long startedAt = System.nanoTime();
                try {
                    job.invoke(index, iteration++);
                    successes++;
                } catch (BusinessException e) {
                    rejections.add(e.getMessage());
                } finally {
                    latencies.add(System.nanoTime() - startedAt);
                }
            }
            return new WorkerResult(successes, rejections,
                    latencies.stream().mapToLong(Long::longValue).toArray());
        });
    }

    /**
     * 统一执行骨架：两段式栅栏保证真正并发，线程索引通过 ThreadLocal 之外的参数下发。
     *
     * <p>请求次数由各场景自己决定（固定次数或固定时长），因此这里只负责收集结果。</p>
     */
    private Measurement execute(String name, int threads, WorkerTask task) throws Exception {
        AtomicInteger threadCounter = new AtomicInteger();
        AtomicLong startedAt = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WorkerResult>> futures = new ArrayList<>(threads);
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    int index = threadCounter.getAndIncrement();
                    start.await(30, TimeUnit.SECONDS);
                    return task.run(index);
                }));
            }
            startedAt.set(System.nanoTime());
            start.countDown();
            int successes = 0;
            List<String> rejections = new ArrayList<>();
            List<Long> latencies = new ArrayList<>();
            for (Future<WorkerResult> future : futures) {
                WorkerResult result = future.get(300, TimeUnit.SECONDS);
                successes += result.successes();
                rejections.addAll(result.rejections());
                for (long latency : result.latencies()) {
                    latencies.add(latency);
                }
            }
            long elapsedNanos = System.nanoTime() - startedAt.get();
            return new Measurement(name, threads, successes, rejections, latencies, elapsedNanos, 0);
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface WorkerTask {
        WorkerResult run(int threadIndex);
    }

    private record WorkerResult(int successes, List<String> rejections, long[] latencies) {
    }

    private record Measurement(String name, int threads, int successes, List<String> rejections,
                               List<Long> latenciesNanos, long elapsedNanos, int cacheKeys) {

        Measurement withCacheKeys(int keys) {
            return new Measurement(name, threads, successes, rejections, latenciesNanos, elapsedNanos, keys);
        }

        int total() {
            return successes + rejections.size();
        }

        double qps() {
            return successes / (elapsedNanos / 1_000_000_000.0);
        }

        double elapsedMillis() {
            return elapsedNanos / 1_000_000.0;
        }

        double percentileMillis(double percentile) {
            long[] sorted = latenciesNanos.stream().mapToLong(Long::longValue).sorted().toArray();
            int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, index))] / 1e6;
        }
    }

    private void printReport(List<Measurement> measurements) {
        StringBuilder report = new StringBuilder("\n===== 搜索链路吞吐测量（QPS）=====\n");
        report.append("基础设施 : ").append(BookingTestInfrastructure.description()).append('\n');
        report.append("数据规模 : 10 家酒店 × 2 房型 × 2 间房，同城专供检索；每页 ").append(PAGE_SIZE)
                .append(" 条；已预热 ").append(WARMUP_REQUESTS).append(" 轮混合请求\n");
        report.append("口径说明 : 所有场景均为稳态窗口（").append(WINDOW_MILLIS).append(" ms × ")
                .append(ROUNDS_PER_LEVEL).append(" 轮，取最后一轮）；失效扰动为固定 ")
                .append(CHURN_WINDOW_MILLIS).append(" ms 窗口\n\n");
        report.append(String.format(Locale.ROOT, "%-24s %6s %8s %8s %9s %8s %8s %8s %9s%n",
                "场景", "线程", "总请求", "成功", "QPS", "P50(ms)", "P95(ms)", "P99(ms)", "耗时(ms)"));
        for (Measurement measurement : measurements) {
            report.append(String.format(Locale.ROOT, "%-24s %6d %8d %8d %9.1f %8.2f %8.2f %8.2f %9.0f%n",
                    measurement.name(), measurement.threads(), measurement.total(), measurement.successes(),
                    measurement.qps(), measurement.percentileMillis(50), measurement.percentileMillis(95),
                    measurement.percentileMillis(99), measurement.elapsedMillis()));
        }
        report.append("\n新增的 Redis 搜索缓存键数量（核对是否真的命中/未命中）：\n");
        for (Measurement measurement : measurements) {
            report.append(String.format(Locale.ROOT, "  %-24s %d 个%n", measurement.name(), measurement.cacheKeys()));
        }
        report.append("================================\n");
        System.out.println(report);
        write(report.toString());
    }

    private void write(String content) {
        try {
            Path path = Path.of("target", "benchmark", "search-throughput-report.txt");
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("搜索吞吐报告落盘失败：" + e.getMessage());
        }
    }
}
