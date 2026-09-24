package com.hotel.vo;

import com.hotel.entity.ChatSession;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话 VO
 */
@Data
public class ChatSessionVO {

    private Long id;
    private String sessionNo;
    private Long hotelId;
    private Integer source;
    /** 状态：0-进行中 1-已结束 2-已转人工 3-超时回收 */
    private Integer status;
    private Integer transferFlag;
    private String transferReason;
    private Integer messageCount;
    private Integer rating;
    private String ratingComment;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    public static ChatSessionVO from(ChatSession s) {
        ChatSessionVO vo = new ChatSessionVO();
        vo.setId(s.getId());
        vo.setSessionNo(s.getSessionNo());
        vo.setHotelId(s.getHotelId());
        vo.setSource(s.getSource());
        vo.setStatus(s.getStatus());
        vo.setTransferFlag(s.getTransferFlag());
        vo.setTransferReason(s.getTransferReason());
        vo.setMessageCount(s.getMessageCount());
        vo.setRating(s.getRating());
        vo.setRatingComment(s.getRatingComment());
        vo.setStartTime(s.getStartTime());
        vo.setEndTime(s.getEndTime());
        return vo;
    }
}
