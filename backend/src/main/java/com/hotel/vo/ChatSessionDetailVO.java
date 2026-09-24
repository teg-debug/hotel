package com.hotel.vo;

import lombok.Data;

import java.util.List;

/**
 * 客服工作台 - 会话详情（会话 + 消息记录 + 用户信息）
 */
@Data
public class ChatSessionDetailVO {

    private ChatSessionAdminVO session;

    /** 用户名 */
    private String username;
    /** 会员等级 */
    private Integer memberLevel;
    /** 历史订单数 */
    private Long orderCount;

    /** 消息记录（升序） */
    private List<ChatMessageVO> messages;
}
