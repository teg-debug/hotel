package com.hotel.service.impl;

import com.hotel.common.BusinessException;
import com.hotel.config.BookingProperties;
import com.hotel.dto.CreateOrderDTO;
import com.hotel.security.UserContext;
import com.hotel.service.BookingService;
import com.hotel.service.OrderPreparation;
import com.hotel.service.OrderTxService;
import com.hotel.service.RoomUnavailableException;
import com.hotel.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 下单编排：锁外只读准备 → 逐个候选房间加「房间级锁」并事务内落单。
 *
 * <p>锁粒度从「房型」下沉到「房间」的原因：房型级锁把同一房型的所有下单串行化，
 * 吞吐上限被钉在 {@code 1 / 临界区耗时}（实测约 23 ms → 42 QPS，与实测一致）。
 * 房间级锁让并行度等于候选房间数，且临界区并未变短——提升完全来自并行度。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {

    /**
     * 单个候选房间的抢锁等待上限与「最多尝试候选数」都来自 {@code app.booking.*} 配置：
     * 实测扫参显示它们与数据库延迟强相关（等待 25/50/100 ms → 吞吐 176.8/174.8/167.8 QPS，
     * 池 10/20/50 → 72/119/175 QPS），写死在代码里会让换个环境就调不动。
     */
    private final RedissonClient redissonClient;
    private final OrderTxService orderTxService;
    private final BookingProperties bookingProperties;

    /** 订单号唯一键冲突时的重试次数 */
    private static final int MAX_RETRY_ON_DUPLICATE = 3;

    @Override
    public OrderVO createOrder(CreateOrderDTO dto) {
        Long userId = UserContext.getUserId();

        // 1. 业务校验（加锁前快速失败，避免无谓抢锁）
        if (!dto.getCheckinDate().isBefore(dto.getCheckoutDate())) {
            throw new BusinessException("离店日期必须晚于入住日期");
        }
        if (dto.getCheckinDate().isBefore(LocalDate.now())) {
            throw new BusinessException("入住日期不能早于今天");
        }

        // 2. 锁外只读准备：酒店/房型校验、金额计算与候选房间快照都不参与库存防重，
        //    放在锁内只会拉长临界区（吞吐上限 = 1 / 临界区耗时）
        OrderPreparation preparation = orderTxService.prepare(dto, userId);
        List<Long> candidates = preparation.candidateRoomIds();
        if (candidates.isEmpty()) {
            throw new BusinessException("该房型库存不足，请更换房型或日期");
        }

        // 3. 逐个候选房间尝试：锁粒度从「房型」下沉到「房间」，并行度由 1 变为候选房间数。
        //    锁只负责降低冲突概率，正确性仍由锁内的 FOR UPDATE 复核与下单后的加锁读兜底，
        //    因此锁失效或候选过期只会降吞吐，不会超订。
        boolean candidateStolen = false;
        boolean lockBusy = false;
        int attempts = Math.min(candidates.size(), bookingProperties.effectiveMaxCandidateAttempts());
        long lockWaitMillis = bookingProperties.effectiveRoomLockWaitMillis();
        for (int i = 0; i < attempts; i++) {
            Long roomId = candidates.get(i);
            RLock lock = redissonClient.getLock("lock:room:" + roomId);
            boolean locked = false;
            try {
                locked = lock.tryLock(lockWaitMillis, TimeUnit.MILLISECONDS);
                if (!locked) {
                    lockBusy = true;
                    continue;
                }
                return createOrderWithRetry(preparation, roomId);
            } catch (RoomUnavailableException e) {
                if (preparation.requestedRoomId() != null) {
                    // 用户指定了房间，没有别的候选可换，原样返回该提示
                    throw e;
                }
                candidateStolen = true;
                log.debug("候选房间已不可用，换下一个候选 roomId={} 原因={}", roomId, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("系统繁忙，请稍后重试");
            } finally {
                // isHeldByCurrentThread 防止锁超时后被其他线程误删
                if (locked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }

        // 4. 候选全部用尽：被抢光，还是自己没抢到锁排队，提示不同
        throw new BusinessException(candidateStolen || !lockBusy
                ? "该房型库存不足，请更换房型或日期"
                : "当前预订人数较多，请稍后重试");
    }

    /**
     * 订单号唯一键冲突时重试。
     *
     * <p>编号已包含毫秒时间戳与随机后缀，冲突概率很低，但一旦发生，
     * 不重试会让用户直接看到一次 500，而重试的成本极低。
     * 每次重试都是一次独立事务，因此事务已随上次异常回滚。</p>
     */
    private OrderVO createOrderWithRetry(OrderPreparation preparation, Long roomId) {
        for (int attempt = 1; attempt <= MAX_RETRY_ON_DUPLICATE; attempt++) {
            try {
                return orderTxService.createOrder(preparation, roomId);
            } catch (DuplicateKeyException e) {
                log.warn("订单号唯一键冲突，进行第 {} 次重试 hotelId={} roomTypeId={} roomId={}",
                        attempt, preparation.hotel().getId(), preparation.roomType().getId(), roomId);
            }
        }
        throw new BusinessException("订单号生成冲突，请稍后重试");
    }
}
