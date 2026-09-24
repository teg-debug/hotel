package com.hotel.service;

import com.hotel.dto.CreateOrderDTO;
import com.hotel.dto.PaymentCallbackDTO;
import com.hotel.entity.BookingOrder;
import com.hotel.vo.OrderVO;

/**
 * 下单与状态流转的事务服务：与 BookingService 分离，保证 @Transactional 经由 Spring 代理生效
 * （避免同类自调用导致事务失效）
 */
public interface OrderTxService {

    /**
     * 锁外只读准备：校验酒店/房型、取用户与会员折扣、计算金额、快照可售房间 ID 池。
     *
     * <p>刻意不加 {@code @Transactional}，且必须在下单方获取分布式锁之前调用：
     * 这些读操作不参与库存防重判定，放在锁内只会拉长临界区、压低热点房型的串行化吞吐。</p>
     */
    OrderPreparation prepare(CreateOrderDTO dto, Long userId);

    /**
     * 事务内针对「指定房间」完成「选房复核 + 生成订单 + 加锁读复核」。
     *
     * <p>房间由调用方从 {@link OrderPreparation#candidateRoomIds()} 中逐个选出并加房间级锁，
     * 因此这里收到的一定是具体房间；该房间若已被占用或状态不可售，抛
     * {@link RoomUnavailableException} 供编排层换下一个候选。</p>
     */
    OrderVO createOrder(OrderPreparation preparation, Long roomId);

    /** 支付成功确认：订单 0→1 + 记录成功流水（幂等，同一事务） */
    void confirmOrder(BookingOrder order, PaymentCallbackDTO dto);

    /**
     * 取消待支付订单：订单 0→3 + 失效搜索缓存（同一事务）。
     *
     * @return 是否取消成功（条件更新影响行数为 0 表示订单已被并发处理）
     */
    boolean cancelOrder(BookingOrder order, String reason);

    /**
     * 取消已确认订单并退款：订单 1→3 + 记录退款流水 + 失效搜索缓存（同一事务）。
     *
     * @return 是否退款成功
     */
    boolean refundOrder(BookingOrder order, String refundNo, String reason);

    /** 入住登记：订单 1-已确认 → 2-已入住（同一事务） */
    void checkIn(BookingOrder order);

    /** 退房：订单 2-已入住 → 4-已完成 + 房间置为打扫中（同一事务） */
    void checkOut(BookingOrder order);
}
