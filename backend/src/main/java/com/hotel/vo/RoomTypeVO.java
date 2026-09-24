package com.hotel.vo;

import com.hotel.entity.RoomType;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 房型 VO（管理端）
 */
@Data
public class RoomTypeVO {

    private Long id;
    private Long hotelId;
    private String name;
    private String bedType;
    private Integer area;
    private Integer maxGuests;
    /** 门市价（元/晚） */
    private BigDecimal price;
    /** 是否含早餐：0-否 1-是 */
    private Integer breakfast;
    private String imgUrl;
    /** 状态：0-停售 1-在售 */
    private Integer status;
    /** 该房型下的房间数 */
    private Long roomCount;

    public static RoomTypeVO from(RoomType roomType) {
        RoomTypeVO vo = new RoomTypeVO();
        vo.setId(roomType.getId());
        vo.setHotelId(roomType.getHotelId());
        vo.setName(roomType.getName());
        vo.setBedType(roomType.getBedType());
        vo.setArea(roomType.getArea());
        vo.setMaxGuests(roomType.getMaxGuests());
        vo.setPrice(roomType.getPrice());
        vo.setBreakfast(roomType.getBreakfast());
        vo.setImgUrl(roomType.getImgUrl());
        vo.setStatus(roomType.getStatus());
        return vo;
    }
}
