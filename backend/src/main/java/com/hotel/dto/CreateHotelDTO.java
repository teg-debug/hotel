package com.hotel.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 新增酒店请求参数
 * <p>经营者调用时可不传 ownerId（自动归属自己）；管理员调用时必传以指定经营者</p>
 */
@Data
public class CreateHotelDTO {

    /** 经营者用户ID（管理员指定；经营者自助注册时忽略，自动归属自己） */
    private Long ownerId;

    @NotBlank(message = "酒店名称不能为空")
    private String name;

    @NotBlank(message = "城市不能为空")
    private String city;

    @NotBlank(message = "地址不能为空")
    private String address;

    @NotNull(message = "星级不能为空")
    @Min(value = 1, message = "星级范围为1-5")
    @Max(value = 5, message = "星级范围为1-5")
    private Integer starLevel;

    private String description;
    private String coverImg;
    /** 酒店图片（逗号分隔URL，可多张） */
    private String images;
    /** 纬度（地理位置，如 31.2402） */
    private BigDecimal latitude;
    /** 经度（地理位置，如 121.4900） */
    private BigDecimal longitude;
    private String phone;
    private String checkinTime = "14:00";
    private String checkoutTime = "12:00";
}
