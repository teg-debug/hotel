package com.hotel.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 修改房间状态请求参数（经营者/管理员/前台）。
 *
 * <p>房间状态只表示物理与运营状态，不代表库存占用：
 * 「是否已被预订」由订单的入住日期区间决定，因此不存在「已订」这个可手动设置的状态。</p>
 */
@Data
public class RoomStatusDTO {

    /** 状态：0-空闲 1-停用维修 2-打扫中 */
    @NotNull(message = "房间状态不能为空")
    @Min(value = 0, message = "房间状态不合法")
    @Max(value = 2, message = "房间状态不合法")
    private Integer status;
}
