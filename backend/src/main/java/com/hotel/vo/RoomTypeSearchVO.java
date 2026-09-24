package com.hotel.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 房型搜索结果 VO
 */
@Data
public class RoomTypeSearchVO {

    private Long id;
    private String name;
    private String bedType;
    private Integer maxGuests;
    private Integer breakfast;
    /** 门市价（元/晚） */
    private BigDecimal price;
    /** 房型图片URL（可放大查看） */
    private String imgUrl;
    /** 指定日期区间内的可用房间数 */
    private Integer availableCount;
}
