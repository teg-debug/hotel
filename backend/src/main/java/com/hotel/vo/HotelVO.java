package com.hotel.vo;

import com.hotel.entity.Hotel;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 酒店信息 VO（管理端列表/编辑用）
 */
@Data
public class HotelVO {

    private Long id;
    /** 经营者用户ID */
    private Long ownerId;
    private String name;
    private String city;
    private String address;
    private Integer starLevel;
    private String description;
    private String coverImg;
    /** 酒店图片（逗号分隔URL） */
    private String images;
    /** 纬度 */
    private BigDecimal latitude;
    /** 经度 */
    private BigDecimal longitude;
    private String phone;
    private String checkinTime;
    private String checkoutTime;
    private Integer status;

    public static HotelVO from(Hotel hotel) {
        HotelVO vo = new HotelVO();
        vo.setId(hotel.getId());
        vo.setOwnerId(hotel.getOwnerId());
        vo.setName(hotel.getName());
        vo.setCity(hotel.getCity());
        vo.setAddress(hotel.getAddress());
        vo.setStarLevel(hotel.getStarLevel());
        vo.setDescription(hotel.getDescription());
        vo.setCoverImg(hotel.getCoverImg());
        vo.setImages(hotel.getImages());
        vo.setLatitude(hotel.getLatitude());
        vo.setLongitude(hotel.getLongitude());
        vo.setPhone(hotel.getPhone());
        vo.setCheckinTime(hotel.getCheckinTime());
        vo.setCheckoutTime(hotel.getCheckoutTime());
        vo.setStatus(hotel.getStatus());
        return vo;
    }
}
