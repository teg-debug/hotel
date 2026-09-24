package com.hotel.booking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「零超订」的核心断言：并发下单不会把同一间房卖两次。
 *
 * <p>被测链路是生产实现本身——真实的 MySQL（InnoDB 行锁 / RR 隔离级别）、
 * 真实的 Redis 与 Redisson 分布式锁、真实的 MyBatis-Plus mapper，
 * 没有任何替身，因此这里的结论可以直接对应线上行为。</p>
 */
@DisplayName("并发下单：零超订")
class BookingOverbookingConcurrencyTest extends BookingIntegrationTestSupport {

    private static final Set<String> SAME_ROOM_FAILURE_MESSAGES =
            Set.of(MSG_ROOM_TAKEN, MSG_LOCK_BUSY, MSG_SYSTEM_BUSY, MSG_THIRD_LAYER);

    private static final Set<String> STOCK_FAILURE_MESSAGES =
            Set.of(MSG_STOCK_NOT_ENOUGH, MSG_LOCK_BUSY, MSG_SYSTEM_BUSY);

    @Test
    @DisplayName("同一房间同一区间并发抢单：只有一单成功，失败全是业务异常，订单行数等于成功数")
    void onlyOneOrderWinsForTheSameRoomAndDateRange() throws Exception {
        // 连跑三轮，避免「偶然抢到一次」被当成结论
        for (int round = 1; round <= 3; round++) {
            Fixture fixture = createFixture(1, "same-room");
            Long roomId = fixture.roomIds().get(0);
            String roomNo = fixture.roomNos().get(0);
            LocalDate checkin = LocalDate.now().plusDays(7);
            LocalDate checkout = checkin.plusDays(2);

            List<Attempt> attempts = runConcurrently(SAME_ROOM_THREADS, fixture.userId(),
                    () -> orderRequest(fixture, roomId, checkin, checkout));

            List<Attempt> succeeded = successes(attempts);

            assertThat(attempts).as("第 %d 轮：每个线程都应拿到一个结果", round).hasSize(SAME_ROOM_THREADS);
            assertThat(succeeded)
                    .as("第 %d 轮：同一房间同一区间只允许一单成功（超订的核心断言）", round)
                    .hasSize(1);
            assertAllFailuresAreBusinessExceptions(attempts, SAME_ROOM_FAILURE_MESSAGES);

            assertThat(succeeded.get(0).order().getRoomNo())
                    .as("第 %d 轮：成功订单必须落在被抢的房间里", round)
                    .isEqualTo(roomNo);
            assertThat(bookedOrders(fixture.hotelId()))
                    .as("第 %d 轮：booking_order 行数必须等于成功数", round)
                    .isEqualTo(succeeded.size());
            assertThat(bookingOrderMapper.countConflict(fixture.hotelId(), fixture.roomTypeId(), checkin, checkout))
                    .as("第 %d 轮：该区间内的有效订单数必须等于成功数", round)
                    .isEqualTo(succeeded.size());
        }
    }

    @Test
    @DisplayName("多房间库存并发下单：成功数恰好等于库存数，既不超卖也不漏卖")
    void sellsExactlyTheAvailableInventoryUnderConcurrency() throws Exception {
        int inventory = 6;
        Fixture fixture = createFixture(inventory, "stock");
        LocalDate checkin = LocalDate.now().plusDays(14);
        LocalDate checkout = checkin.plusDays(1);

        // 不指定房间：由系统在同一房型里自动分配
        List<Attempt> attempts = runConcurrently(SAME_ROOM_THREADS, fixture.userId(),
                () -> orderRequest(fixture, null, checkin, checkout));

        List<Attempt> succeeded = successes(attempts);

        assertThat(succeeded)
                .as("6 间房面对 32 个并发请求，成功数必须恰好等于库存数")
                .hasSize(inventory);
        assertAllFailuresAreBusinessExceptions(attempts, STOCK_FAILURE_MESSAGES);
        assertThat(bookedOrders(fixture.hotelId()))
                .as("booking_order 行数必须等于成功数")
                .isEqualTo(succeeded.size());
        assertThat(succeeded.stream().map(attempt -> attempt.order().getRoomNo()).distinct().count())
                .as("成功的订单必须落在不同房间上，不能重复占用同一间房")
                .isEqualTo(inventory);
        assertThat(succeeded.stream().map(attempt -> attempt.order().getOrderNo()).distinct().count())
                .as("订单号不能重复")
                .isEqualTo(inventory);
        assertThat(roomMapper.selectAvailableRooms(fixture.hotelId(), fixture.roomTypeId(), checkin, checkout))
                .as("库存卖光后该区间不应再返回任何可售房间")
                .isEmpty();
    }

    @Test
    @DisplayName("不同区间可正常复用同一房间：防重逻辑不误伤不重叠的预订")
    void allowsNonOverlappingBookingsOnTheSameRoom() {
        Fixture fixture = createFixture(1, "non-overlap");
        Long roomId = fixture.roomIds().get(0);
        String roomNo = fixture.roomNos().get(0);
        LocalDate firstCheckin = LocalDate.now().plusDays(30);
        LocalDate firstCheckout = firstCheckin.plusDays(2);

        assertThat(createOrderAs(fixture.userId(),
                orderRequest(fixture, roomId, firstCheckin, firstCheckout)).getRoomNo()).isEqualTo(roomNo);

        // 紧邻但不重叠：入住日 = 上一单离店日
        assertThat(createOrderAs(fixture.userId(),
                orderRequest(fixture, roomId, firstCheckout, firstCheckout.plusDays(2))).getRoomNo())
                .as("区间不重叠时应能正常下单，防止「零超订」被做成「一律拒绝」")
                .isEqualTo(roomNo);

        assertThat(bookedOrders(fixture.hotelId())).isEqualTo(2);
    }
}
