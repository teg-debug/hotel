package com.hotel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客服对话消息
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    /** 发送者：用户 */
    public static final int SENDER_USER = 0;
    /** 发送者：AI助手 */
    public static final int SENDER_AI = 1;
    /** 发送者：人工客服/前台 */
    public static final int SENDER_HUMAN = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话ID */
    private Long sessionId;

    /** 发送者：0-用户 1-AI助手 2-人工客服/前台 */
    private Integer sender;

    /** 消息内容 */
    private String content;

    /** 类型：0-文本 1-订单卡片 2-工单卡片 3-富文本/链接 */
    private Integer msgType;

    /** 意图标签（预订/查房态/服务请求/投诉/闲聊等） */
    private String intentTag;

    /** 抽取实体（JSON 字符串：日期/房型/数量等） */
    private String entities;

    /** 触发的工具名（如有） */
    private String toolName;

    /** Token 消耗 */
    private Integer tokenCount;

    private LocalDateTime createTime;
}
