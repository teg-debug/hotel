package com.hotel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能客服会话
 */
@Data
@TableName("chat_session")
public class ChatSession {

    /** 会话状态：进行中 */
    public static final int STATUS_ACTIVE = 0;
    /** 会话状态：已结束 */
    public static final int STATUS_ENDED = 1;
    /** 会话状态：已转人工 */
    public static final int STATUS_TRANSFERRED = 2;
    /** 会话状态：超时回收 */
    public static final int STATUS_EXPIRED = 3;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话编号（业务唯一） */
    private String sessionNo;

    /** 发起用户ID */
    private Long userId;

    /** 酒店ID（可为空=全局咨询） */
    private Long hotelId;

    /** 来源：0-用户端 1-前台助手 */
    private Integer source;

    /** 状态：0-进行中 1-已结束 2-已转人工 3-超时回收 */
    private Integer status;

    /** 转人工标记：0-否 1-已转人工 */
    private Integer transferFlag;

    /** 转人工原因 */
    private String transferReason;

    /** 会话标签：已解决/转技术/投诉（客服人工标记） */
    private String tag;

    /** 接手的人工客服/前台用户ID */
    private Long staffUserId;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 消息总数 */
    private Integer messageCount;

    /** 满意度评价：1-5星 */
    private Integer rating;

    /** 评价内容 */
    private String ratingComment;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
