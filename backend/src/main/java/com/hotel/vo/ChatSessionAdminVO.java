package com.hotel.vo;

import com.hotel.entity.ChatSession;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客服工作台 - 会话列表项（含用户信息）
 */
@Data
public class ChatSessionAdminVO {

    private Long id;
    private String sessionNo;
    private Long userId;
    /** 用户名 */
    private String username;
    /** 会员等级：0-普通 1-银卡 2-金卡 */
    private Integer memberLevel;
    /** 历史订单数 */
    private Long orderCount;
    private Long hotelId;
    private Integer source;
    /** 状态：0-进行中 1-已结束 2-已转人工 3-超时回收 */
    private Integer status;
    private Integer transferFlag;
    private String transferReason;
    /** 会话标签：已解决/转技术/投诉 */
    private String tag;
    private Integer rating;
    private Integer messageCount;
    private LocalDateTime startTime;
    /** 会话持续时长（分钟） */
    private Long durationMin;
    /** 是否待人工处理（已转人工且尚未被接管） */
    private Boolean pending;

    public static ChatSessionAdminVO from(ChatSession s) {
        ChatSessionAdminVO vo = new ChatSessionAdminVO();
        vo.setId(s.getId());
        vo.setSessionNo(s.getSessionNo());
        vo.setUserId(s.getUserId());
        vo.setHotelId(s.getHotelId());
        vo.setSource(s.getSource());
        vo.setStatus(s.getStatus());
        vo.setTransferFlag(s.getTransferFlag());
        vo.setTransferReason(s.getTransferReason());
        vo.setTag(s.getTag());
        vo.setRating(s.getRating());
        vo.setMessageCount(s.getMessageCount());
        vo.setStartTime(s.getStartTime());
        if (s.getStartTime() != null) {
            vo.setDurationMin(Math.max(1, java.time.Duration.between(
                    s.getStartTime(), java.time.LocalDateTime.now()).toMinutes()));
        }
        // 已转人工且未打"已解决"标签 → 待人工处理
        vo.setPending(s.getStatus() != null && s.getStatus() == 2
                && !"已解决".equals(s.getTag()));
        return vo;
    }
}
