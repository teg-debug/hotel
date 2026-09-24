package com.hotel.booking;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hotel.common.BusinessException;
import com.hotel.dto.CreateOrderDTO;
import com.hotel.entity.BookingOrder;
import com.hotel.entity.Hotel;
import com.hotel.entity.Room;
import com.hotel.entity.RoomType;
import com.hotel.entity.User;
import com.hotel.mapper.BookingOrderMapper;
import com.hotel.mapper.HotelMapper;
import com.hotel.mapper.RoomMapper;
import com.hotel.mapper.RoomTypeMapper;
import com.hotel.mapper.UserMapper;
import com.hotel.security.UserContext;
import com.hotel.service.BookingService;
import com.hotel.vo.OrderVO;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下单并发用例的公共基座：真实 MySQL + 真实 Redis + 真实 Redisson 锁。
 *
 * <p>关键约定：</p>
 * <ul>
 *   <li>每个用例都新建独立的酒店/房型/房间/用户夹具，互不干扰，
 *       也不会与 {@code schema.sql} 里的种子数据或其他用例抢同一把锁；</li>
 *   <li>{@link #runConcurrently} 用统一栅栏让所有线程「同时」下单，
 *       否则线程调度会把并发场景退化成串行场景，断言就失去意义；</li>
 *   <li>{@code UserContext} 是 ThreadLocal，工作线程必须自行设置身份，
 *       否则 {@code createOrder} 取不到 userId。</li>
 * </ul>
 */
@SpringBootTest(classes = BookingTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
abstract class BookingIntegrationTestSupport {

    /** 同一房间抢单的并发线程数 */
    protected static final int SAME_ROOM_THREADS = 32;

    /** 分布式锁失效时的并发线程数（压力直接落到数据库） */
    protected static final int LOCK_BROKEN_THREADS = 16;

    // ---- 服务层在并发路径上可能给出的业务提示，用于断言「没有 500 级异常泄漏」----
    protected static final String MSG_LOCK_BUSY = "当前预订人数较多，请稍后重试";
    protected static final String MSG_SYSTEM_BUSY = "系统繁忙，请稍后重试";
    protected static final String MSG_ROOM_TAKEN = "您选择的房间已被占用或不可用，请重新选择房间";
    protected static final String MSG_STOCK_NOT_ENOUGH = "该房型库存不足，请更换房型或日期";
    /** 第三层「加锁读复核」给出的提示 */
    protected static final String MSG_THIRD_LAYER = "该房间在所选日期已被预订，请重新选择房间";

    @Autowired
    protected BookingService bookingService;
    @Autowired
    protected BookingOrderMapper bookingOrderMapper;
    @Autowired
    protected HotelMapper hotelMapper;
    @Autowired
    protected RoomTypeMapper roomTypeMapper;
    @Autowired
    protected RoomMapper roomMapper;
    @Autowired
    protected UserMapper userMapper;

    private int fixtureSequence;

    @DynamicPropertySource
    static void testInfrastructureProperties(DynamicPropertyRegistry registry) {
        BookingTestInfrastructure.start();
        registry.add("spring.datasource.url", BookingTestInfrastructure::jdbcUrl);
        registry.add("spring.datasource.username", BookingTestInfrastructure::username);
        registry.add("spring.datasource.password", BookingTestInfrastructure::password);
        // 锁失效场景下并发线程会一起压到数据库，连接池必须放得下
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> SAME_ROOM_THREADS);
        registry.add("spring.data.redis.host", BookingTestInfrastructure::redisHost);
        registry.add("spring.data.redis.port", BookingTestInfrastructure::redisPort);
        registry.add("spring.data.redis.database", BookingTestInfrastructure::redisDatabase);
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    // ===================== 夹具 =====================

    /** 一次并发用例的独立数据：一家酒店 + 一个房型 + 若干房间 + 一个下单用户 */
    protected record Fixture(Long hotelId, Long roomTypeId, Long userId,
                             List<Long> roomIds, List<String> roomNos, String label) {
    }

    protected Fixture createFixture(int roomCount, String label) {
        String unique = label + "-" + (++fixtureSequence) + "-" + System.nanoTime();

        User owner = new User();
        owner.setUsername("owner-" + unique);
        owner.setPassword("$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG");
        owner.setNickname("并发用例经营者");
        owner.setMemberLevel(0);
        owner.setRole(1);
        owner.setStatus(1);
        userMapper.insert(owner);

        User guest = new User();
        guest.setUsername("guest-" + unique);
        guest.setPassword("$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG");
        guest.setNickname("并发用例下单人");
        guest.setMemberLevel(0);
        guest.setRole(0);
        guest.setStatus(1);
        userMapper.insert(guest);

        Hotel hotel = new Hotel();
        hotel.setOwnerId(owner.getId());
        hotel.setName("并发用例酒店-" + unique);
        hotel.setCity("上海");
        hotel.setAddress("并发用例路 1 号");
        hotel.setStarLevel(3);
        hotel.setStatus(1);
        hotelMapper.insert(hotel);

        RoomType roomType = new RoomType();
        roomType.setHotelId(hotel.getId());
        roomType.setName("并发用例房型");
        roomType.setBedType("大床");
        roomType.setArea(30);
        roomType.setMaxGuests(2);
        roomType.setPrice(new BigDecimal("680.00"));
        roomType.setBreakfast(0);
        roomType.setStatus(1);
        roomTypeMapper.insert(roomType);

        List<Long> roomIds = new ArrayList<>(roomCount);
        List<String> roomNos = new ArrayList<>(roomCount);
        for (int i = 0; i < roomCount; i++) {
            Room room = new Room();
            room.setHotelId(hotel.getId());
            room.setRoomTypeId(roomType.getId());
            room.setRoomNo("T" + (800 + i));
            room.setFloor(8);
            room.setStatus(Room.STATUS_IDLE);
            roomMapper.insert(room);
            roomIds.add(room.getId());
            roomNos.add(room.getRoomNo());
        }
        return new Fixture(hotel.getId(), roomType.getId(), guest.getId(), roomIds, roomNos, unique);
    }

    protected CreateOrderDTO orderRequest(Fixture fixture, Long roomId, LocalDate checkin, LocalDate checkout) {
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setHotelId(fixture.hotelId());
        dto.setRoomTypeId(fixture.roomTypeId());
        dto.setRoomId(roomId);
        dto.setCheckinDate(checkin);
        dto.setCheckoutDate(checkout);
        dto.setGuestName("并发用例入住人");
        dto.setGuestPhone("13800000000");
        return dto;
    }

    // ===================== 执行与断言 =====================

    /** 单次下单尝试：成功则带订单，失败则带异常，二选一 */
    protected record Attempt(OrderVO order, Throwable error) {
        boolean succeeded() {
            return error == null;
        }
    }

    /**
     * 让 {@code threadCount} 个线程尽可能同时发起下单。
     *
     * <p>两段式栅栏：先等所有线程就绪，再统一放行，确保真正并发。</p>
     */
    protected List<Attempt> runConcurrently(int threadCount, Long userId,
                                            Supplier<CreateOrderDTO> requestFactory) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Attempt>> futures = new ArrayList<>(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    UserContext.set(userId, "concurrency-test", 0);
                    ready.countDown();
                    try {
                        start.await(30, TimeUnit.SECONDS);
                        return new Attempt(bookingService.createOrder(requestFactory.get()), null);
                    } catch (Throwable t) {
                        return new Attempt(null, t);
                    } finally {
                        UserContext.clear();
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("所有线程应在 30 秒内完成就绪").isTrue();
            start.countDown();

            List<Attempt> attempts = new ArrayList<>(threadCount);
            for (Future<Attempt> future : futures) {
                attempts.add(future.get(120, TimeUnit.SECONDS));
            }
            return attempts;
        } finally {
            executor.shutdownNow();
        }
    }

    /** 以指定用户身份下单（服务层从 ThreadLocal 取 userId） */
    protected OrderVO createOrderAs(Long userId, CreateOrderDTO dto) {
        UserContext.set(userId, "concurrency-test", 0);
        try {
            return bookingService.createOrder(dto);
        } finally {
            UserContext.clear();
        }
    }

    protected static List<Attempt> successes(List<Attempt> attempts) {
        return attempts.stream().filter(Attempt::succeeded).toList();
    }

    protected static List<Attempt> failures(List<Attempt> attempts) {
        return attempts.stream().filter(attempt -> !attempt.succeeded()).toList();
    }

    /** 失败请求必须都是业务异常，且提示语在预期集合内——防止重试耗尽等场景漏出 500 */
    protected static void assertAllFailuresAreBusinessExceptions(List<Attempt> attempts, Set<String> allowedMessages) {
        List<Attempt> failed = failures(attempts);
        assertThat(failed).as("并发抢占必然产生失败请求，用例本身应能观察到冲突").isNotEmpty();
        for (Attempt attempt : failed) {
            assertThat(attempt.error())
                    .as("失败请求必须是 BusinessException，不能泄漏其它异常")
                    .isInstanceOf(BusinessException.class);
            assertThat(attempt.error().getMessage())
                    .as("异常提示应为预期的业务提示")
                    .isIn(allowedMessages.toArray());
        }
    }

    /** 落库的订单行数（仅限当前夹具的酒店，避免受其它数据干扰） */
    protected long bookedOrders(Long hotelId) {
        return bookingOrderMapper.selectCount(
                Wrappers.<BookingOrder>lambdaQuery().eq(BookingOrder::getHotelId, hotelId));
    }

    /** 直接写一条有效订单（绕过服务层，用于构造「既成事实」的并发前置状态） */
    protected BookingOrder insertOrderDirectly(Fixture fixture, Long roomId, LocalDate checkin, LocalDate checkout) {
        BookingOrder order = new BookingOrder();
        order.setOrderNo("T" + System.nanoTime() + (int) (Math.random() * 1000));
        order.setUserId(fixture.userId());
        order.setHotelId(fixture.hotelId());
        order.setHotelName("并发用例酒店");
        order.setRoomTypeId(fixture.roomTypeId());
        order.setRoomTypeName("并发用例房型");
        order.setRoomId(roomId);
        order.setRoomNo("TEST");
        order.setCheckinDate(checkin);
        order.setCheckoutDate(checkout);
        order.setNightCount((int) (checkout.toEpochDay() - checkin.toEpochDay()));
        order.setGuestName("并发用例入住人");
        order.setGuestPhone("13800000000");
        order.setRoomPrice(new BigDecimal("680.00"));
        order.setMemberDiscount(BigDecimal.ONE);
        order.setTotalAmount(new BigDecimal("680.00").multiply(BigDecimal.valueOf(order.getNightCount())));
        order.setStatus(0);
        bookingOrderMapper.insert(order);
        return order;
    }
}
