package com.hotel.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 我的订单查询参数
 */
@Data
public class OrderQueryDTO {

    /** 订单状态筛选（不传查全部） */
    private Integer status;

    @Min(value = 1, message = "页码最小为1")
    private Integer page = 1;

    @Min(value = 1, message = "每页条数最小为1")
    @Max(value = 100, message = "每页条数最大为100")
    private Integer size = 10;
}
