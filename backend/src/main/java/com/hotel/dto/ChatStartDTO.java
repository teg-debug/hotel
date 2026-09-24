package com.hotel.dto;

import lombok.Data;

/**
 * 创建会话请求参数
 */
@Data
public class ChatStartDTO {

    /** 酒店ID（可为空=全局咨询） */
    private Long hotelId;

    /** 会话来源：0-用户端 1-前台助手 */
    private Integer source = 0;
}
