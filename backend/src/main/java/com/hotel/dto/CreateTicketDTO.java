package com.hotel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建服务工单请求参数（供 AI 工具调用，也可 REST 手动创建）
 */
@Data
public class CreateTicketDTO {

    @NotNull(message = "会话ID不能为空")
    private Long sessionId;

    @NotNull(message = "酒店ID不能为空")
    private Long hotelId;

    /** 关联房间ID（送物类工单，可选） */
    private Long roomId;

    @NotBlank(message = "请求类型不能为空")
    private String requestType;

    /** 需求描述 */
    private String content;

    /** 优先级：1-普通 2-紧急 */
    private Integer priority = 1;
}
