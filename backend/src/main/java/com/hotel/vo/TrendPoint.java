package com.hotel.vo;

import lombok.Data;

/**
 * 对话量趋势点（按日）
 */
@Data
public class TrendPoint {

    /** 日期 yyyy-MM-dd */
    private String date;

    /** 当日会话数 */
    private long count;

    public TrendPoint(String date, long count) {
        this.date = date;
        this.count = count;
    }
}
