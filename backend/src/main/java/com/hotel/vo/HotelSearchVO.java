package com.hotel.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 酒店搜索结果 VO
 */
@Data
public class HotelSearchVO {

    private Long hotelId;
    private String hotelName;
    private String city;
    private String address;
    private Integer starLevel;
    /** 酒店介绍 */
    private String description;
    private String coverImg;
    /** 酒店图片（逗号分隔URL，可放大查看） */
    private String images;
    /** 纬度（地理位置） */
    private BigDecimal latitude;
    /** 经度（地理位置） */
    private BigDecimal longitude;
    /** 该酒店可订房型的最低起价 */
    private BigDecimal lowestPrice;
    /** 可选房型列表（含日期区间可用数量） */
    private List<RoomTypeSearchVO> availableRoomTypes;
}
