package com.hotel.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 并发用例的测试基础设施：真实 MySQL + 真实 Redis。
 *
 * <p>「零超订」依赖 InnoDB 的行锁、事务隔离级别与 Redisson 的分布式锁语义，
 * 用 H2 或嵌入式 Redis 替身测不出真实行为，所以这里必须起真家伙。</p>
 *
 * <p>优先用 Testcontainers 拉起一次性 MySQL / Redis（完全隔离、可入 CI）；
 * 若当前机器没有可用的 Docker，则回退到本机已启动的 MySQL / Redis，
 * 并在独立的 {@code hotel_test} 库与 Redis 15 号库上跑，避免污染业务数据。
 * 显式指定模式：{@code -Dhotel.test.infra=containers|local}。</p>
 *
 * <p>回退模式可用的参数（系统属性优先，其次环境变量）：
 * {@code hotel.test.mysql.url/username/password}、
 * {@code hotel.test.redis.host/port/database}。</p>
 */
final class BookingTestInfrastructure {

    private static final Logger log = LoggerFactory.getLogger(BookingTestInfrastructure.class);

    /** 建表脚本（与生产同源，避免测试用另一套 DDL 造成口径漂移） */
    private static final String SCHEMA_LOCATION = "sql/schema.sql";

    private static final String MODE_PROPERTY = "hotel.test.infra";
    private static final String MODE_ENV = "HOTEL_TEST_INFRA";

    private static final String MYSQL_IMAGE = "mysql:8.0";
    private static final String REDIS_IMAGE = "redis:7-alpine";

    /** 回退模式下使用的专用库与库号，避免动到业务库 */
    private static final String DEFAULT_LOCAL_MYSQL_URL =
            "jdbc:mysql://localhost:3306/hotel_test"
                    + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai"
                    + "&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String DEFAULT_LOCAL_MYSQL_USERNAME = "root";
    private static final String DEFAULT_LOCAL_MYSQL_PASSWORD = "root";
    private static final String DEFAULT_LOCAL_REDIS_HOST = "localhost";
    private static final int DEFAULT_LOCAL_REDIS_PORT = 6379;
    private static final int DEFAULT_LOCAL_REDIS_DATABASE = 15;

    /** 容器内统一使用的库名与账号（容器与回退模式口径一致，脚本只需一份） */
    private static final String DATABASE = "hotel_test";
    private static final String DB_USERNAME = "hotel";
    private static final String DB_PASSWORD = "hotel";

    private static final int REDIS_PORT = 6379;

    /** jdbc:mysql://host:port/database?params —— 拆出 host:port，用于免库连接建库 */
    private static final Pattern MYSQL_URL_PATTERN =
            Pattern.compile("jdbc:mysql://([^/?]+)/([^?]*)(\\?.*)?");

    private static final Object LOCK = new Object();
    private static volatile boolean started;

    private static volatile String jdbcUrl;
    private static volatile String username;
    private static volatile String password;
    private static volatile String redisHost;
    private static volatile int redisPort;
    private static volatile int redisDatabase;
    private static volatile String description;

    /** 容器句柄必须强引用，否则可能被判定为无用对象而提前回收 */
    @SuppressWarnings("unused")
    private static MySQLContainer<?> mysqlContainer;
    @SuppressWarnings("unused")
    private static GenericContainer<?> redisContainer;

    private BookingTestInfrastructure() {
    }

    /** 幂等启动：多次调用只解析一次基础设施 */
    static synchronized void start() {
        if (started) {
            return;
        }
        synchronized (LOCK) {
            if (started) {
                return;
            }
            switch (mode()) {
                case "containers", "docker" -> startContainers();
                case "local", "host" -> startLocal();
                default -> startAutomatically();
            }
            started = true;
            log.info("测试基础设施就绪：{} | MySQL={} | Redis={}:{}/db{}",
                    description, jdbcUrl, redisHost, redisPort, redisDatabase);
        }
    }

    static String jdbcUrl() {
        return jdbcUrl;
    }

    static String username() {
        return username;
    }

    static String password() {
        return password;
    }

    static String redisHost() {
        return redisHost;
    }

    static int redisPort() {
        return redisPort;
    }

    static int redisDatabase() {
        return redisDatabase;
    }

    /** 当前实际使用的基础设施描述（容器 / 本机），用于把测量口径写进报告 */
    static String description() {
        return description;
    }

    // ================= 三种启动策略 =================

    private static void startAutomatically() {
        if (dockerAvailable()) {
            startContainers();
            return;
        }
        String localUrl = property("hotel.test.mysql.url", "HOTEL_TEST_MYSQL_URL", DEFAULT_LOCAL_MYSQL_URL);
        Matcher matcher = MYSQL_URL_PATTERN.matcher(localUrl);
        if (matcher.matches() && isReachable(hostOf(matcher.group(1)), portOf(matcher.group(1)))) {
            log.warn("未检测到可用的 Docker，改用本机 MySQL/Redis 执行并发用例（库 {} / Redis db{}）；"
                            + "需要完全隔离的环境时请安装 Docker 或指定 -D{}=containers",
                    databaseOf(localUrl), DEFAULT_LOCAL_REDIS_DATABASE, MODE_PROPERTY);
            startLocal();
            return;
        }
        throw new IllegalStateException("""
                未找到可用的 Docker，也未发现本机 MySQL(%s)。
                并发用例需要真实 MySQL 与 Redis，请二选一：
                  1) 安装并启动 Docker（默认即可，无需额外配置）；
                  2) 启动本机 MySQL/Redis 后指定：-Dhotel.test.infra=local \
                -Dhotel.test.mysql.url=... -Dhotel.test.mysql.username=... -Dhotel.test.mysql.password=...\
                """.formatted(localUrl));
    }

    private static void startContainers() {
        MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse(MYSQL_IMAGE))
                .withDatabaseName(DATABASE)
                .withUsername(DB_USERNAME)
                .withPassword(DB_PASSWORD)
                .withUrlParam("useUnicode", "true")
                .withUrlParam("characterEncoding", "UTF-8")
                .withUrlParam("serverTimezone", "Asia/Shanghai")
                .withUrlParam("useSSL", "false")
                .withUrlParam("allowPublicKeyRetrieval", "true");
        mysql.start();
        mysqlContainer = mysql;

        GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                .withExposedPorts(REDIS_PORT);
        redis.start();
        redisContainer = redis;

        jdbcUrl = mysql.getJdbcUrl();
        username = mysql.getUsername();
        password = mysql.getPassword();
        redisHost = redis.getHost();
        redisPort = redis.getMappedPort(REDIS_PORT);
        redisDatabase = 0;
        description = "Testcontainers(" + MYSQL_IMAGE + " + " + REDIS_IMAGE + ")";
        applySchema();
    }

    private static void startLocal() {
        String url = property("hotel.test.mysql.url", "HOTEL_TEST_MYSQL_URL", DEFAULT_LOCAL_MYSQL_URL);
        String user = property("hotel.test.mysql.username", "HOTEL_TEST_MYSQL_USER", DEFAULT_LOCAL_MYSQL_USERNAME);
        String secret = property("hotel.test.mysql.password", "HOTEL_TEST_MYSQL_PASSWORD",
                DEFAULT_LOCAL_MYSQL_PASSWORD);
        redisHost = property("hotel.test.redis.host", "HOTEL_TEST_REDIS_HOST", DEFAULT_LOCAL_REDIS_HOST);
        redisPort = intProperty("hotel.test.redis.port", "HOTEL_TEST_REDIS_PORT", DEFAULT_LOCAL_REDIS_PORT);
        redisDatabase = intProperty("hotel.test.redis.database", "HOTEL_TEST_REDIS_DATABASE",
                DEFAULT_LOCAL_REDIS_DATABASE);

        try {
            createDatabase(url, user, secret);
            jdbcUrl = url;
            username = user;
            password = secret;
            applySchema();
        } catch (SQLException e) {
            throw new IllegalStateException("连接本机 MySQL 失败：" + url
                    + "（可用 -Dhotel.test.mysql.url/-Dhotel.test.mysql.username/-Dhotel.test.mysql.password 覆盖）", e);
        }
        description = "本机 MySQL + Redis（未使用 Docker）";
    }

    // ================= 建库与建表 =================

    /** 用免库连接创建测试库，避免依赖「本机恰好已有该库」 */
    private static void createDatabase(String url, String user, String secret) throws SQLException {
        Matcher matcher = MYSQL_URL_PATTERN.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("无法解析 MySQL 连接串：" + url);
        }
        String serverUrl = "jdbc:mysql://" + matcher.group(1) + "/" + (matcher.group(3) == null ? "" : matcher.group(3));
        String database = matcher.group(2);
        try (Connection connection = DriverManager.getConnection(serverUrl, user, secret);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + database + "` "
                    + "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        log.info("测试库已就绪：{}", database);
    }

    /** 执行生产同一份建表脚本；仅去掉 CREATE DATABASE / USE 两行，改由连接串决定库 */
    private static void applySchema() {
        String script = sanitizedSchema();
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)),
                            StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("初始化测试表结构失败：" + e.getMessage(), e);
        }
    }

    private static String sanitizedSchema() {
        ClassPathResource resource = new ClassPathResource(SCHEMA_LOCATION);
        try (InputStream in = resource.getInputStream()) {
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            StringBuilder filtered = new StringBuilder(script.length());
            for (String line : script.split("\r?\n")) {
                String normalized = line.strip().toLowerCase(Locale.ROOT);
                if (normalized.startsWith("create database") || normalized.startsWith("use ")) {
                    continue;
                }
                filtered.append(line).append('\n');
            }
            return filtered.toString();
        } catch (IOException e) {
            throw new IllegalStateException("读取建表脚本失败：" + SCHEMA_LOCATION, e);
        }
    }

    // ================= 小工具 =================

    private static String mode() {
        return property(MODE_PROPERTY, MODE_ENV, "auto").strip().toLowerCase(Locale.ROOT);
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            log.warn("Docker 可用性探测失败，按不可用处理：{}", t.toString());
            return false;
        }
    }

    private static boolean isReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String hostOf(String hostAndPort) {
        int index = hostAndPort.lastIndexOf(':');
        return index < 0 ? hostAndPort : hostAndPort.substring(0, index);
    }

    private static int portOf(String hostAndPort) {
        int index = hostAndPort.lastIndexOf(':');
        return index < 0 ? 3306 : Integer.parseInt(hostAndPort.substring(index + 1));
    }

    private static String databaseOf(String url) {
        Matcher matcher = MYSQL_URL_PATTERN.matcher(url);
        return matcher.matches() ? matcher.group(2) : "?";
    }

    private static String property(String key, String envKey, String fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(envKey);
        }
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intProperty(String key, String envKey, int fallback) {
        String value = property(key, envKey, null);
        return value == null ? fallback : Integer.parseInt(value);
    }
}
