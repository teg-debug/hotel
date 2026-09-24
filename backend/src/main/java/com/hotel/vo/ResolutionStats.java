package com.hotel.vo;

import lombok.Data;

/**
 * 解决率统计：AI 解决 vs 转人工 vs 进行中
 */
@Data
public class ResolutionStats {

    /** AI 解决会话数（已结束且未转人工） */
    private long aiResolved;
    /** 转人工会话数 */
    private long transferred;
    /** 进行中会话数 */
    private long active;
    /** 会话总数 */
    private long total;

    /** AI 解决率 = aiResolved / total */
    public double getAiResolveRate() {
        return total == 0 ? 0 : Math.round(aiResolved * 1000.0 / total) / 10.0;
    }

    /** 转人工率 */
    public double getTransferRate() {
        return total == 0 ? 0 : Math.round(transferred * 1000.0 / total) / 10.0;
    }
}
