package com.hotel.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发送消息请求参数
 */
@Data
public class ChatMessageDTO {

    @NotBlank(message = "消息内容不能为空")
    private String content;
}
