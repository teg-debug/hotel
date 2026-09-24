package com.hotel.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 管理端订单查询参数（经营者/管理员）
 */
@Data
public class AdminOrderQueryDTO {

    /** 酒店ID（经营者可筛选名下某酒店） */
    private Long hotelId;

    /** 订单状态筛选 */
    private Integer status;

    @Min(value = 1, message = "页码最小为1")
    private Integer page = 1;

    @Min(value = 1, message = "每页条数最小为1")
    @Max(value = 100, message = "每页条数最大为100")
    private Integer size = 10;
}
