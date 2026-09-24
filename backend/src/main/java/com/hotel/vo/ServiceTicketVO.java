package com.hotel.vo;

import com.hotel.entity.ServiceTicket;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务工单 VO
 */
@Data
public class ServiceTicketVO {

    private Long id;
    private String ticketNo;
    private Long sessionId;
    private Long userId;
    private Long hotelId;
    private Long roomId;
    private String requestType;
    private String content;
    private Integer priority;
    /** 状态：0-待处理 1-处理中 2-已完成 3-已取消 */
    private Integer status;
    private Long assigneeId;
    private String handleResult;
    private LocalDateTime handledTime;
    private LocalDateTime createTime;

    public static ServiceTicketVO from(ServiceTicket t) {
        ServiceTicketVO vo = new ServiceTicketVO();
        vo.setId(t.getId());
        vo.setTicketNo(t.getTicketNo());
        vo.setSessionId(t.getSessionId());
        vo.setUserId(t.getUserId());
        vo.setHotelId(t.getHotelId());
        vo.setRoomId(t.getRoomId());
        vo.setRequestType(t.getRequestType());
        vo.setContent(t.getContent());
        vo.setPriority(t.getPriority());
        vo.setStatus(t.getStatus());
        vo.setAssigneeId(t.getAssigneeId());
        vo.setHandleResult(t.getHandleResult());
        vo.setHandledTime(t.getHandledTime());
        vo.setCreateTime(t.getCreateTime());
        return vo;
    }
}
