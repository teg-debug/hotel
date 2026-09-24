package com.hotel.vo;

import com.hotel.entity.ChatMessage;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息 VO
 */
@Data
public class ChatMessageVO {

    private Long id;
    private Long sessionId;
    /** 发送者：0-用户 1-AI助手 2-人工客服 */
    private Integer sender;
    private String content;
    /** 类型：0-文本 1-订单卡片 2-工单卡片 3-富文本 */
    private Integer msgType;
    private String intentTag;
    private String toolName;
    private LocalDateTime createTime;

    public static ChatMessageVO from(ChatMessage m) {
        ChatMessageVO vo = new ChatMessageVO();
        vo.setId(m.getId());
        vo.setSessionId(m.getSessionId());
        vo.setSender(m.getSender());
        vo.setContent(m.getContent());
        vo.setMsgType(m.getMsgType());
        vo.setIntentTag(m.getIntentTag());
        vo.setToolName(m.getToolName());
        vo.setCreateTime(m.getCreateTime());
        return vo;
    }
}
