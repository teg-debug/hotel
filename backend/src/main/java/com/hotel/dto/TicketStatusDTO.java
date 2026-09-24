package com.hotel.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 工单状态更新请求参数（前台处理：处理中/已完成）
 */
@Data
public class TicketStatusDTO {

    @NotNull(message = "状态不能为空")
    @Min(value = 1, message = "状态只能为处理中(1)或已完成(2)")
    @Max(value = 2, message = "状态只能为处理中(1)或已完成(2)")
    private Integer status;

    /** 处理结果（标记完成时必填，由服务层校验） */
    private String handleResult;
}
