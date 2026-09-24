package com.hotel.booking;

import com.hotel.common.BusinessException;
import com.hotel.entity.Room;
import com.hotel.mapper.RoomMapper;
import com.hotel.vo.OrderVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

/**
 * 第三层兜底：{@code BookingOrderMapper.lockOverlappingOrderIds} 的加锁读复核。
 *
 * <p>为什么第三层需要单独验证：正常路径下，第一层 Redisson 锁与
 * 第二层选房 {@code FOR UPDATE} 已经把同一房间的并发下单串行化了，
 * 第三层基本不会被触发——正因如此它很容易变成一段「没人走过」的死代码
 * （写错列名、漏掉状态过滤都不会被发现）。这里通过注入第二层失效
 * （选房不再校验日期冲突）把真实的第三层分支逼出来，验证它确实会
 * 拦下重复售出并回滚整笔事务。</p>
 */
@DisplayName("并发下单：第三层加锁读复核兜底")
class BookingThirdLayerGuardTest extends BookingIntegrationTestSupport {

    /** 第二层失明后由选房方法返回的虚拟房间（房间行是否存在不影响第三层的加锁读） */
    private static final String VIRTUAL_ROOM_NO = "V900001";

    /** 虚拟房间的 ID 必须逐用例唯一：用例内部会直接往 booking_order 写「既成事实」，同 ID 会串味 */
    private static final AtomicLong VIRTUAL_ROOM_IDS = new AtomicLong(9_000_000L);

    /** 重叠订单的未提交持有时长：留足余量，让「加锁读被阻塞」的判定在慢机器上也稳定 */
    private static final long HOLD_MILLIS = 3000L;

    @MockitoBean
    private RoomMapper roomMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long virtualRoomId;

    @BeforeEach
    void assignVirtualRoom() {
        virtualRoomId = VIRTUAL_ROOM_IDS.incrementAndGet();
    }

    @Test
    @DisplayName("第二层失明时，第三层仍拦下同房间同区间的重复售出并回滚")
    void thirdLayerCatchesDuplicateSaleWhenRoomSelectionIsBlind() {
        Fixture fixture = createFixture(0, "third-layer");
        blindSecondLayer(fixture);
        LocalDate checkin = LocalDate.now().plusDays(40);
        LocalDate checkout = checkin.plusDays(2);

        OrderVO first = createOrderAs(fixture.userId(),
                orderRequest(fixture, virtualRoomId, checkin, checkout));
        assertThat(first.getRoomNo()).isEqualTo(VIRTUAL_ROOM_NO);

        // 第二单与第一单重叠，但选房被注入失效而放行 —— 必须由第三层的加锁读复核拦下。
        // 这里刻意「串行」发起：第二层失明时若再并发插入，两个事务会在加锁读上互等对方
        // 未提交的行而触发 InnoDB 死锁回滚，断言异常类型会变得不稳定。
        Throwable failure = catchThrowable(() -> createOrderAs(fixture.userId(),
                orderRequest(fixture, virtualRoomId, checkin, checkout)));

        assertThat(failure)
                .as("第三层检测到同一房间的重叠订单后应抛业务异常")
                .isInstanceOf(BusinessException.class)
                .hasMessage(MSG_THIRD_LAYER);
        assertThat(bookedOrders(fixture.hotelId()))
                .as("被第三层拦下的订单必须随事务回滚，库里只剩第一单")
                .isEqualTo(1);
        assertThat(bookingOrderMapper.countConflict(fixture.hotelId(), fixture.roomTypeId(), checkin, checkout))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("第三层是加锁读（当前读）：会等待未提交的重叠订单，而不是读到旧快照")
    void thirdLayerGuardIsACurrentReadThatWaitsForUncommittedOrders() throws Exception {
        Fixture fixture = createFixture(0, "third-layer-lock");
        LocalDate checkin = LocalDate.now().plusDays(40);
        LocalDate checkout = checkin.plusDays(1);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        CountDownLatch inserted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // 事务 A：插入一条重叠订单后保持未提交约 3 秒
            Future<?> holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
                insertOrderDirectly(fixture, virtualRoomId, checkin, checkout);
                inserted.countDown();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(HOLD_MILLIS));
            }));
            assertThat(inserted.await(30, TimeUnit.SECONDS)).as("事务 A 应在 30 秒内插入重叠订单").isTrue();

            long startedAt = System.nanoTime();
            List<Long> overlapping = transactions.execute(status ->
                    bookingOrderMapper.lockOverlappingOrderIds(virtualRoomId, checkin, checkout));
            long blockedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            holder.get(30, TimeUnit.SECONDS);

            assertThat(blockedMillis)
                    .as("加锁读必须被未提交的重叠订单阻塞住（快照读会立刻返回 0 条，第三层就形同虚设）")
                    .isGreaterThanOrEqualTo(HOLD_MILLIS / 3);
            assertThat(overlapping)
                    .as("对方提交后应能读到这条重叠订单，从而触发 size() > 1 的拦截分支")
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 注入「第二层失效」：选房不再校验日期冲突，直接返回目标房间 */
    private void blindSecondLayer(Fixture fixture) {
        Room room = new Room();
        room.setId(virtualRoomId);
        room.setHotelId(fixture.hotelId());
        room.setRoomTypeId(fixture.roomTypeId());
        room.setRoomNo(VIRTUAL_ROOM_NO);
        room.setFloor(9);
        room.setStatus(Room.STATUS_IDLE);
        doReturn(room).when(roomMapper).selectAvailableRoomById(any(), any(), any(), any(), any());
    }
}
