package com.hotel.service;

import com.hotel.entity.Hotel;
import com.hotel.entity.RoomType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 下单前的「锁外只读准备」结果。
 *
 * <p>酒店/房型校验、金额计算、候选房间快照都不参与库存防重判定，因此被移出分布式锁的临界区：
 * 结果固化在本对象里，锁内只保留「选房 + 落单 + 复核」。热点房型的吞吐由
 * {@code 1 / 锁持有时间} 决定，临界区每缩短一点，串行化吞吐就抬升一分。</p>
 *
 * <p>{@code candidateRoomIds} 是「房间级锁」的尝试顺序：用户指定了房间时只有它一个候选；
 * 否则是锁外快照来的可售房间 ID 池，且已打散——若所有请求都从同一间房开始抢，
 * 房间级锁会退化成「第一间房的锁」，热点依旧。</p>
 *
 * <p>代价是这些读值比在锁内取用时早了几毫秒，即存在一个毫秒级窗口：期间若房型被停售或调价，
 * 本单仍按准备时的快照成交；候选房间也可能已被别人抢走。这是有意的取舍——前者与
 * 「用户看到的价格即成交价」语义一致，后者由锁内的加锁读复核剔除并换下一个候选。</p>
 */
public record OrderPreparation(Long userId,
                               Hotel hotel,
                               RoomType roomType,
                               int nights,
                               BigDecimal unitPrice,
                               BigDecimal discount,
                               BigDecimal totalAmount,
                               LocalDate checkinDate,
                               LocalDate checkoutDate,
                               String guestName,
                               String guestPhone,
                               Long requestedRoomId,
                               List<Long> candidateRoomIds) {
}
