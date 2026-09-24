package com.hotel.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务工单（送水/六小件/清洁等，流转到前台处理）
 */
@Data
@TableName("service_ticket")
public class ServiceTicket {

    /** 状态：待处理 */
    public static final int STATUS_PENDING = 0;
    /** 状态：处理中 */
    public static final int STATUS_PROCESSING = 1;
    /** 状态：已完成 */
    public static final int STATUS_DONE = 2;
    /** 状态：已取消 */
    public static final int STATUS_CANCELED = 3;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工单编号 */
    private String ticketNo;

    /** 来源会话ID */
    private Long sessionId;

    /** 发起用户ID */
    private Long userId;

    /** 酒店ID */
    private Long hotelId;

    /** 关联房间ID（送物类工单） */
    private Long roomId;

    /** 请求类型：送水/送六小件/清洁提醒/其他 */
    private String requestType;

    /** 需求描述 */
    private String content;

    /** 优先级：1-普通 2-紧急 */
    private Integer priority;

    /** 状态：0-待处理 1-处理中 2-已完成 3-已取消 */
    private Integer status;

    /** 处理人（前台用户ID） */
    private Long assigneeId;

    /** 处理结果 */
    private String handleResult;

    /** 处理完成时间 */
    private LocalDateTime handledTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
