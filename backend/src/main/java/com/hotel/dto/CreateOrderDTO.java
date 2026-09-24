package com.hotel.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/**
 * 创建订单请求参数
 */
@Data
public class CreateOrderDTO {

    @NotNull(message = "酒店ID不能为空")
    private Long hotelId;

    @NotNull(message = "房型ID不能为空")
    private Long roomTypeId;

    @NotNull(message = "入住日期不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate checkinDate;

    @NotNull(message = "离店日期不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate checkoutDate;

    @NotBlank(message = "入住人姓名不能为空")
    private String guestName;

    @NotBlank(message = "入住人电话不能为空")
    private String guestPhone;

    /** 用户指定房间ID（可选，不传则由系统自动分配） */
    private Long roomId;
}
