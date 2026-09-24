package com.hotel.service;

import com.hotel.dto.CreateOrderDTO;
import com.hotel.vo.OrderVO;

/**
 * 预订下单：锁外完成只读校验与算价，锁内按「房间级锁 + 事务复核」逐个候选房间尝试，防超卖。
 *
 * <p>锁粒度是房间而不是房型：房型级锁定会把同一房型的下单串行化，吞吐上限被钉在
 * {@code 1 / 临界区耗时}；房间级锁把并行度提到候选房间数。锁只降低冲突概率，
 * 正确性由锁内的 {@code FOR UPDATE} 复核与下单后的加锁读复核兜底。</p>
 */
public interface BookingService {

    OrderVO createOrder(CreateOrderDTO dto);
}
