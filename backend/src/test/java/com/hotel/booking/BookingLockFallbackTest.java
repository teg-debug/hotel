package com.hotel.booking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「锁失效」场景：把 Redisson 分布式锁换成一把假锁（永远加锁成功），
 * 验证第一层防护完全失效时，数据库侧的第二层（选房 FOR UPDATE 行锁）
 * 与第三层（下单后 {@code lockOverlappingOrderIds} 加锁读复核）仍能兜住「零超订」。
 *
 * <p>为什么要在真实数据库上做这件事：分布式锁会因锁租期到期、Redis 抖动、
 * 键误删等原因「看似加锁成功实则没有互斥」，这类故障无法用单元测试发现，
 * 只能把锁拆掉后让并发压力真正打到数据库上，看数据是否被写坏。</p>
 */
@DisplayName("并发下单：分布式锁失效时的数据库兜底")
class BookingLockFallbackTest extends BookingIntegrationTestSupport {

    private static final Set<String> FALLBACK_FAILURE_MESSAGES =
            Set.of(MSG_ROOM_TAKEN, MSG_STOCK_NOT_ENOUGH, MSG_THIRD_LAYER, MSG_LOCK_BUSY, MSG_SYSTEM_BUSY);

    @MockitoBean
    private RedissonClient redissonClient;

    @BeforeEach
    void breakTheDistributedLock() throws Exception {
        RLock fakeLock = mock(RLock.class);
        // 永远「加锁成功」：等价于分布式锁完全失效
        doReturn(true).when(fakeLock).tryLock(anyLong(), any(TimeUnit.class));
        doReturn(true).when(fakeLock).isHeldByCurrentThread();
        doReturn(fakeLock).when(redissonClient).getLock(anyString());
    }

    @Test
    @DisplayName("假锁场景下同一房间并发抢单：仍然只有一单成功，行数等于成功数")
    void stillRejectsOverbookingWhenDistributedLockIsBroken() throws Exception {
        Fixture fixture = createFixture(1, "lock-broken");
        Long roomId = fixture.roomIds().get(0);
        String roomNo = fixture.roomNos().get(0);
        LocalDate checkin = LocalDate.now().plusDays(7);
        LocalDate checkout = checkin.plusDays(2);

        List<Attempt> attempts = runConcurrently(LOCK_BROKEN_THREADS, fixture.userId(),
                () -> orderRequest(fixture, roomId, checkin, checkout));

        List<Attempt> succeeded = successes(attempts);

        assertThat(succeeded)
                .as("分布式锁失效后，数据库侧必须拦住重复售出")
                .hasSize(1);
        assertAllFailuresAreBusinessExceptions(attempts, FALLBACK_FAILURE_MESSAGES);
        assertThat(bookedOrders(fixture.hotelId()))
                .as("booking_order 行数必须等于成功数")
                .isEqualTo(succeeded.size());
        assertThat(succeeded.get(0).order().getRoomNo()).isEqualTo(roomNo);
    }

    @Test
    @DisplayName("假锁场景下多房间库存并发下单：卖出数量不超过库存数")
    void neverSellsMoreThanInventoryWhenDistributedLockIsBroken() throws Exception {
        int inventory = 4;
        Fixture fixture = createFixture(inventory, "lock-broken-stock");
        LocalDate checkin = LocalDate.now().plusDays(21);
        LocalDate checkout = checkin.plusDays(1);

        List<Attempt> attempts = runConcurrently(LOCK_BROKEN_THREADS, fixture.userId(),
                () -> orderRequest(fixture, null, checkin, checkout));

        List<Attempt> succeeded = successes(attempts);

        assertThat(succeeded)
                .as("无分布式锁时成功数不得超过库存数")
                .hasSizeLessThanOrEqualTo(inventory);
        assertThat(succeeded.stream().map(attempt -> attempt.order().getRoomNo()).distinct().count())
                .as("同一间房不能被两单同时占用")
                .isEqualTo(succeeded.size());
        assertAllFailuresAreBusinessExceptions(attempts, FALLBACK_FAILURE_MESSAGES);
        assertThat(bookedOrders(fixture.hotelId())).isEqualTo(succeeded.size());
    }
}
