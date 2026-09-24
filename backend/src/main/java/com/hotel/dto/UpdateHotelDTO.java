package com.hotel.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 修改酒店信息请求参数（经营者/管理员）
 */
@Data
public class UpdateHotelDTO {

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
    /** 酒店图片（逗号分隔URL） */
    private String images;
    /** 纬度 */
    private BigDecimal latitude;
    /** 经度 */
    private BigDecimal longitude;
    private String phone;
    private String checkinTime;
    private String checkoutTime;

    /** 状态：0-下架 1-营业中；不传表示保持原状态 */
    @Min(value = 0, message = "酒店状态不合法")
    @Max(value = 1, message = "酒店状态不合法")
    private Integer status;
}
