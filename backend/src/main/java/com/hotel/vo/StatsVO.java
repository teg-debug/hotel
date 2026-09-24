package com.hotel.vo;

import lombok.Data;

/**
 * 入住率统计 VO
 */
@Data
public class StatsVO {

    private Long hotelId;
    private String hotelName;
    /** 酒店房间总数 */
    private Long totalRooms;
    /** 可售间夜数 = 总房间数 × 天数 */
    private Long capacityNights;
    /** 已售间夜数（已确认/已入住/已完成订单） */
    private Long occupiedNights;
    /** 入住率（百分比，保留 1 位小数） */
    private Double occupancyRate;
    /** 有效订单数 */
    private Long orderCount;
}
