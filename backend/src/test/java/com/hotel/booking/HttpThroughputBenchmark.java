package com.hotel.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.dto.CreateOrderDTO;
import com.hotel.security.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接口层吞吐测量：真实 HTTP、真实 Tomcat、真实 JWT 拦截器与 JSON 序列化。
 *
 * <p>与 Service 层基准的区别只有一层 Web，因此两者相减就是「HTTP 栈成本」
 * （连接、请求解析、拦截器、参数绑定、序列化、响应写出）。</p>
 *
 * <p>口径说明：</p>
 * <ul>
 *   <li>QPS 定义为「窗口内总请求数 ÷ 窗口耗时」，与服务层基准一致，
 *       业务拒绝也计入请求数（它同样占用了接口层的处理能力）；成功率单列；</li>
 *   <li>成败以响应体 {@code Result.code} 为准而非 HTTP 200：业务异常会被全局异常处理器
 *       按语义映射为 4xx（库存不足即 HTTP 422），把「非 200」当成故障会得出完全错误的结论。
 *       只有 5xx、连接异常与不符合 {@code Result} 契约的响应体才算基础设施层失败，
 *       并断言必须为 0；</li>
 *   <li>搜索场景跑两轮取第二轮（稳态），下单场景只跑一轮：库存会被真实消耗，
 *       第二轮面对的是「已售罄」这个不同状态，取平均值没有意义；</li>
 *   <li>鉴权按生产逻辑来：签发 JWT 并写入 {@code auth:token:{userId}}
 *       （等价于登录时的动作），否则拦截器会直接 401，测的就不是目标链路了。</li>
 * </ul>
 *
 * <p>默认不参与构建，显式运行：{@code mvn test -Dtest=HttpThroughputBenchmark}</p>
 */
@SpringBootTest(classes = HttpBenchmarkApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("接口层吞吐测量（真实 HTTP）")
class HttpThroughputBenchmark extends BookingIntegrationTestSupport {

    private static final int[] CONCURRENCY_LEVELS = {8, 16, 32};

    /** 稳态窗口长度；下单场景只跑一轮，搜索场景跑两轮取第二轮 */
    private static final long WINDOW_MILLIS = 1500;

    private static final int SEARCH_ROUNDS = 3;

    /** 测量前的 HTTP 预热次数：JIT、连接、Redis 连接都热起来再计时 */
    private static final int HTTP_WARMUP_REQUESTS = 300;

    /** 热点夹具的房量：够大才能让多数请求落在「成功」路径上，与服务层基准同形 */
    private static final int HOT_INVENTORY = 384;

    /** 与 createFixture 建店时一致的城市 */
    private static final String SEARCH_CITY = "上海";

    /** 接口层用到的 JWT 配置（生产由环境变量提供，测试里给一个专用密钥） */
    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> "http-benchmark-only-secret-key-not-for-production-use");
        registry.add("jwt.expire", () -> 3600);
        // 登录态本地缓存默认关闭（与生产默认一致）；需要量化它的收益时开启：
        //   $env:HOTEL_TEST_TOKEN_CACHE_MILLIS=2000  或  -Dhotel.test.token-cache-millis=2000
        registry.add("app.security.token-cache-millis", () -> System.getProperty(
                "hotel.test.token-cache-millis",
                System.getenv().getOrDefault("HOTEL_TEST_TOKEN_CACHE_MILLIS", "0")));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /** 每个用户只签发一次 token，避免把签名开销算进测量窗口 */
    private final Map<Long, String> tokens = new ConcurrentHashMap<>();

    /** 下单散列场景的全局日期游标：保证每个请求都落在没人用过的入住日上 */
    private final AtomicLong dateCursor = new AtomicLong();

    @Test
    @DisplayName("测量搜索与下单两个主链路的接口层 QPS，并与服务层基准对比")
    void measureHttpThroughput() throws Exception {
        Long searchUserId = createFixture(1, "http-search").userId();
        Fixture hotFixture = createFixture(HOT_INVENTORY, "http-hot");
        List<Fixture> spreadFixtures = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY_LEVELS[CONCURRENCY_LEVELS.length - 1]; i++) {
            spreadFixtures.add(createFixture(1, "http-spread-" + i));
        }
        // token 在测量窗口之前全部签发好，避免把签名与 Redis 写算进窗口
        spreadFixtures.forEach(fixture -> token(fixture.userId()));
        LocalDate base = LocalDate.now().plusDays(5);

        String searchToken = token(searchUserId);
        HttpRequest warmSearch = searchRequest(searchToken, base, base.plusDays(2));
        assertThat(send(newClient(), warmSearch).successful()).as("预热搜索应成功").isTrue();
        warmUpHttp(searchToken, base);

        List<Measurement> measurements = new ArrayList<>();
        for (int threads : CONCURRENCY_LEVELS) {
            measurements.add(searchCacheHit(threads, searchToken, base));
        }
        for (int threads : CONCURRENCY_LEVELS) {
            measurements.add(searchCacheMiss(threads, searchToken, base));
        }
        Measurement hot = orderHotspot(32, hotFixture, base);
        measurements.add(hot);
        for (int threads : CONCURRENCY_LEVELS) {
            measurements.add(orderSpread(threads, spreadFixtures, base));
        }

        printReport(measurements);

        assertThat(measurements).allSatisfy(measurement -> {
            assertThat(measurement.infrastructureFailures())
                    .as("%s：不应出现 5xx、连接异常或不可解析的响应体", measurement.name())
                    .isEmpty();
            assertThat(measurement.successes())
                    .as("%s：应至少有一次成功请求", measurement.name())
                    .isPositive();
        });

        // 接口层的「零超订」：成功响应数必须等于落库订单数，且不超出库存
        assertThat(hot.successes())
                .as("同一房型热点下单：成功响应数应等于落库订单数")
                .isEqualTo((int) bookedOrders(hotFixture.hotelId()));
        assertThat(hot.successes())
                .as("成功数不应超过库存")
                .isLessThanOrEqualTo(HOT_INVENTORY);

        measurements.stream()
                .filter(measurement -> measurement.name().startsWith("搜索"))
                .forEach(measurement -> assertThat(measurement.rejections())
                        .as("%s：搜索是读路径，不应出现业务拒绝", measurement.name())
                        .isZero());
    }

    // ===================== 四个场景 =====================

    /**
     * 测量前把整条 HTTP 路径热起来：JIT 编译、连接建立、Redis 连接、序列化器都就绪后再计时。
     *
     * <p>不预热时最早跑的几个档位会明显偏慢（曾观察到同一场景 8/16 线程 1.2k QPS、
     * 32 线程 8.4k QPS 的假象），因为测量窗口里混进了 JIT 与连接建立的成本。</p>
     */
    private void warmUpHttp(String token, LocalDate base) throws Exception {
        HttpClient client = newClient();
        HttpRequest search = searchRequest(token, base, base.plusDays(2));
        for (int i = 0; i < HTTP_WARMUP_REQUESTS; i++) {
            send(client, search);
        }
        // 下单路径单独热一次：用独立夹具与独立日期，避免消耗后续夹具的库存
        Fixture warmup = createFixture(2, "http-warmup");
        String warmupToken = token(warmup.userId());
        for (int i = 0; i < 20; i++) {
            LocalDate checkin = base.plusDays(600 + i);
            send(client, orderRequest(warmupToken, orderJson(
                    orderRequest(warmup, null, checkin, checkin.plusDays(1)))));
        }
    }

    /** 搜索·缓存命中：同一串查询参数重复请求，命中 5 分钟缓存 */
    private Measurement searchCacheHit(int threads, String token, LocalDate base) throws Exception {
        HttpRequest request = searchRequest(token, base, base.plusDays(2));
        return steady("搜索·命中(HTTP)", threads, (threadIndex, iteration) -> request, SEARCH_ROUNDS);
    }

    /** 搜索·缓存未命中：每次请求用不同入住日 → 缓存键不同 → 必然回源 */
    private Measurement searchCacheMiss(int threads, String token, LocalDate base) throws Exception {
        AtomicLong offset = new AtomicLong();
        return steady("搜索·回源(HTTP)", threads, (threadIndex, iteration) -> {
            LocalDate checkin = base.plusDays(offset.getAndIncrement());
            return searchRequest(token, checkin, checkin.plusDays(1));
        }, SEARCH_ROUNDS);
    }

    /** 下单·热点：所有请求打同一房型、由服务端自动选房，库存有限 */
    private Measurement orderHotspot(int threads, Fixture fixture, LocalDate base) throws Exception {
        String token = token(fixture.userId());
        LocalDate checkout = base.plusDays(2);
        String body = orderJson(orderRequest(fixture, null, base, checkout));
        return steady("下单·热点(HTTP)", threads,
                (threadIndex, iteration) -> orderRequest(token, body), 1);
    }

    /** 下单·散列：每个线程用自己的酒店房型，且每次请求用不重叠的入住区间，因此应当全部成功 */
    private Measurement orderSpread(int threads, List<Fixture> fixtures, LocalDate base) throws Exception {
        return steady("下单·散列(HTTP)", threads, (threadIndex, iteration) -> {
            Fixture fixture = fixtures.get(threadIndex);
            // 日期游标全局递增：跨档位、跨轮次都不复用同一天，否则后一档测的会是「已售罄」
            LocalDate checkin = base.plusDays(200 + dateCursor.getAndIncrement());
            String body = orderJson(orderRequest(fixture, null, checkin, checkin.plusDays(1)));
            return orderRequest(token(fixture.userId()), body);
        }, 1);
    }

    // ===================== HTTP 小工具 =====================

    private HttpClient newClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private String token(Long userId) {
        return tokens.computeIfAbsent(userId, id -> {
            String token = jwtUtil.createToken(id, "http-benchmark", 0);
            // 与登录一致：token 落到 Redis，拦截器会与请求头比对
            stringRedisTemplate.opsForValue().set("auth:token:" + id, token, Duration.ofHours(1));
            return token;
        });
    }

    private HttpRequest searchRequest(String token, LocalDate checkin, LocalDate checkout) {
        StringBuilder query = new StringBuilder("city=").append(encode(SEARCH_CITY))
                .append("&page=1&size=10");
        if (checkin != null) {
            query.append("&checkin=").append(checkin);
        }
        if (checkout != null) {
            query.append("&checkout=").append(checkout);
        }
        return HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/hotels/search?" + query))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
    }

    private HttpRequest orderRequest(String token, String body) {
        return HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/orders/create"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json;charset=UTF-8")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private String orderJson(CreateOrderDTO dto) throws Exception {
        // 用应用自己的 ObjectMapper 序列化，保证请求体的日期/数字格式与生产一致
        return objectMapper.writeValueAsString(dto);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ===================== 测量骨架 =====================

    @FunctionalInterface
    private interface HttpJob {
        HttpRequest request(int threadIndex, int iteration) throws Exception;
    }

    private record Response(int status, int code, String msg) {
        boolean successful() {
            return status == 200 && code == 200;
        }
    }

    private Response send(HttpClient client, HttpRequest request) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return parse(response);
    }

    private Response parse(HttpResponse<String> response) {
        int code = -1;
        String msg = "";
        try {
            var node = objectMapper.readTree(response.body());
            code = node.path("code").asInt(-1);
            msg = node.path("msg").asText("");
        } catch (Exception ignored) {
            msg = "(响应体无法解析)";
        }
        return new Response(response.statusCode(), code, msg);
    }

    /**
     * 跑 rounds 轮固定时长窗口，返回最后一轮（第一轮用于消化线程爬升与连接建立）。
     *
     * <p>每个工作线程用自己的 {@link HttpClient}：JDK 客户端的 I/O 由单个 selector 线程驱动，
     * 所有请求挤一个实例时客户端会先饱和，测出来的就成了「客户端上限」而不是服务端能力。
     * 真实负载本就来自大量独立客户端，因此这里也不共用单个实例。</p>
     */
    private Measurement steady(String name, int threads, HttpJob job, int rounds) throws Exception {
        Measurement last = null;
        for (int round = 0; round < rounds; round++) {
            last = window(name, threads, WINDOW_MILLIS, job);
        }
        return last;
    }

    private Measurement window(String name, int threads, long durationMillis, HttpJob job) throws Exception {
        AtomicInteger index = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WorkerResult>> futures = new ArrayList<>(threads);
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    HttpClient client = newClient();
                    int threadIndex = index.getAndIncrement();
                    start.await(30, TimeUnit.SECONDS);
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMillis);
                    int successes = 0;
                    int rejections = 0;
                    int iteration = 0;
                    List<Long> latencies = new ArrayList<>();
                    Map<String, Integer> failureReasons = new TreeMap<>();
                    Map<String, Integer> statusCounts = new TreeMap<>();
                    Map<String, Integer> infrastructureFailures = new TreeMap<>();
                    while (System.nanoTime() < deadline) {
                        HttpRequest request = job.request(threadIndex, iteration++);
                        long startedAt = System.nanoTime();
                        try {
                            Response response = parse(client.send(request,
                                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
                            if (response.status() >= 500 || response.code() <= 0) {
                                // 5xx 或不符 Result 契约：属于基础设施层失败，必须为 0
                                infrastructureFailures.merge(response.code() <= 0
                                        ? "HTTP " + response.status() + "（响应体不可解析）"
                                        : "HTTP " + response.status(), 1, Integer::sum);
                            } else if (response.successful()) {
                                successes++;
                            } else {
                                // 业务拒绝：状态码与响应体 code 都记下来（业务异常映射为 HTTP 422）
                                rejections++;
                                statusCounts.merge("HTTP " + response.status(), 1, Integer::sum);
                                failureReasons.merge(response.msg().isBlank() ? "(无提示)" : response.msg(),
                                        1, Integer::sum);
                            }
                        } catch (Exception e) {
                            infrastructureFailures.merge(e.getClass().getSimpleName(), 1, Integer::sum);
                        } finally {
                            latencies.add(System.nanoTime() - startedAt);
                        }
                    }
                    return new WorkerResult(successes, rejections, failureReasons, statusCounts,
                            infrastructureFailures, latencies.stream().mapToLong(Long::longValue).toArray());
                }));
            }
            long startedAt = System.nanoTime();
            start.countDown();
            int successes = 0;
            int rejections = 0;
            Map<String, Integer> reasons = new TreeMap<>();
            Map<String, Integer> statusCounts = new TreeMap<>();
            Map<String, Integer> infrastructureFailures = new TreeMap<>();
            List<Long> latencies = new ArrayList<>();
            for (Future<WorkerResult> future : futures) {
                WorkerResult result = future.get(300, TimeUnit.SECONDS);
                successes += result.successes();
                rejections += result.rejections();
                result.failureReasons().forEach((key, value) -> reasons.merge(key, value, Integer::sum));
                result.statusCounts().forEach((key, value) -> statusCounts.merge(key, value, Integer::sum));
                result.infrastructureFailures()
                        .forEach((key, value) -> infrastructureFailures.merge(key, value, Integer::sum));
                for (long latency : result.latencies()) {
                    latencies.add(latency);
                }
            }
            long elapsedNanos = System.nanoTime() - startedAt;
            return new Measurement(name, threads, successes, rejections, reasons, statusCounts,
                    infrastructureFailures, latencies, elapsedNanos);
        } finally {
            executor.shutdownNow();
        }
    }

    private record WorkerResult(int successes, int rejections, Map<String, Integer> failureReasons,
                                Map<String, Integer> statusCounts, Map<String, Integer> infrastructureFailures,
                                long[] latencies) {
    }

    private record Measurement(String name, int threads, int successes, int rejections,
                               Map<String, Integer> rejectionReasons, Map<String, Integer> statusCounts,
                               Map<String, Integer> infrastructureFailures,
                               List<Long> latenciesNanos, long elapsedNanos) {

        int total() {
            return successes + rejections
                    + infrastructureFailures.values().stream().mapToInt(Integer::intValue).sum();
        }

        double qps() {
            return total() / (elapsedNanos / 1_000_000_000.0);
        }

        double successRate() {
            return total() == 0 ? 0 : 100.0 * successes / total();
        }

        double percentileMillis(double percentile) {
            long[] sorted = latenciesNanos.stream().mapToLong(Long::longValue).sorted().toArray();
            if (sorted.length == 0) {
                return 0;
            }
            int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, index))] / 1e6;
        }

        String topReason() {
            return rejectionReasons.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(entry -> entry.getKey() + " ×" + entry.getValue())
                    .orElse("—");
        }
    }

    // ===================== 报告 =====================

    private void printReport(List<Measurement> measurements) {
        StringBuilder report = new StringBuilder("\n===== 接口层吞吐测量（真实 HTTP）=====\n");
        report.append("基础设施 : ").append(BookingTestInfrastructure.description()).append('\n');
        report.append("接口     : GET /api/v1/hotels/search、POST /api/v1/orders/create（均经 JWT 拦截器鉴权）\n");
        report.append("窗口     : ").append(WINDOW_MILLIS).append(" ms；搜索跑 ").append(SEARCH_ROUNDS)
                .append(" 轮取第二轮，下单只跑一轮（库存会被消耗，第二轮已是不同状态）\n");
        report.append("QPS 口径 : 窗口内总请求数 ÷ 耗时（业务拒绝同样计入，它同样占用接口层处理能力）\n\n");
        report.append(String.format(Locale.ROOT, "%-18s %6s %9s %9s %8s %8s %8s %8s%n",
                "场景", "线程", "总请求", "QPS", "成功率", "P50(ms)", "P95(ms)", "P99(ms)"));
        for (Measurement measurement : measurements) {
            report.append(String.format(Locale.ROOT, "%-18s %6d %9d %9.1f %7.1f%% %8.2f %8.2f %8.2f%n",
                    measurement.name(), measurement.threads(), measurement.total(), measurement.qps(),
                    measurement.successRate(), measurement.percentileMillis(50),
                    measurement.percentileMillis(95), measurement.percentileMillis(99)));
        }
        report.append("\n主要拒绝原因：\n");
        for (Measurement measurement : measurements) {
            report.append(String.format(Locale.ROOT, "  %-18s %s%n", measurement.name(), measurement.topReason()));
        }
        report.append("\n业务拒绝的 HTTP 状态分布（业务异常按语义映射为 4xx，故「非 200」不等于故障）：\n");
        for (Measurement measurement : measurements) {
            report.append(String.format(Locale.ROOT, "  %-18s %s%n", measurement.name(),
                    measurement.statusCounts().isEmpty() ? "无（全部成功）" : measurement.statusCounts().toString()));
        }
        report.append("\n基础设施层失败（5xx / 连接异常 / 响应体不符 Result 契约，应为空）：\n");
        for (Measurement measurement : measurements) {
            report.append(String.format(Locale.ROOT, "  %-18s %s%n", measurement.name(),
                    measurement.infrastructureFailures().isEmpty()
                            ? "无"
                            : measurement.infrastructureFailures().toString()));
        }
        report.append("====================================\n");
        System.out.println(report);
        write(report.toString());
    }

    private void write(String content) {
        try {
            Path path = Path.of("target", "benchmark", "http-throughput-report.txt");
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("接口层吞吐报告落盘失败：" + e.getMessage());
        }
    }
}
