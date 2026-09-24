package com.hotel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 下单并发控制的可调参数。
 *
 * <p>这三个值都经过实测扫参（32 线程单房型热点，见 {@code BookingLockTuningSweep}），
 * 且与运行环境的数据库延迟强相关，因此外置成配置而不是写死在代码里：</p>
 *
 * <ul>
 *   <li>{@code candidatePoolSize}：候选房间池大小，也是房间级锁并行度的上限。
 *       实测池 10 / 20 / 50 对应吞吐 72 / 119 / 175 QPS、首次请求成功率 28~39% / 49~62% / 86~96%，
 *       是三个旋钮里影响最大的一个。</li>
 *   <li>{@code maxCandidateAttempts}：单请求最多尝试的候选房间数，买的是「首次成功率」，
 *       代价是更多无效事务（池 50 下 3 → 8：成功率 88.0% → 95.8%，吞吐 -8%）。</li>
 *   <li>{@code roomLockWaitMillis}：单候选房间的抢锁等待上限。拿不到就换下一个候选，
 *       因此单请求最坏等待 = 本值 × 最多尝试候选数。实测对吞吐影响很小，
 *       但等太短会把争抢转成更多重试、P95 反而变差。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.booking")
public class BookingProperties {

    /** 候选房间池大小（必须 ≥ 1，且应明显大于 maxCandidateAttempts 以获得分散的候选起点） */
    private int candidatePoolSize = 50;

    /** 单请求最多尝试的候选房间数（必须 ≥ 1） */
    private int maxCandidateAttempts = 8;

    /** 单候选房间的抢锁等待上限（毫秒，必须 ≥ 1） */
    private long roomLockWaitMillis = 50;

    /** 配置写错时不至于让下单彻底不可用：非法值一律回落到 1 */
    public int effectiveCandidatePoolSize() {
        return Math.max(candidatePoolSize, 1);
    }

    public int effectiveMaxCandidateAttempts() {
        return Math.max(maxCandidateAttempts, 1);
    }

    public long effectiveRoomLockWaitMillis() {
        return Math.max(roomLockWaitMillis, 1L);
    }
}
