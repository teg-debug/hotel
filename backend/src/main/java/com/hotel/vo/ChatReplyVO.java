package com.hotel.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天回复 VO（同步接口返回 / WebSocket 推送均使用）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatReplyVO {

    private Long sessionId;

    /** AI 回答内容 */
    private String reply;

    /** 意图标签 */
    private String intent;

    /** 回答置信度（0-1；RAG 命中取相似度，工具/闲聊取 1.0） */
    private Double confidence;

    /** 是否需要转人工 */
    private Boolean needTransfer;

    /** 转人工原因（needTransfer=true 时有值） */
    private String transferReason;

    /** 回答来源：knowledge-知识库直答 / llm-大模型 / rule-规则兜底 / transfer-已转人工 */
    private String source;
}
