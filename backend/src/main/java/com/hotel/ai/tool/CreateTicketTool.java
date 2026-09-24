package com.hotel.ai.tool;

import com.hotel.common.BusinessException;
import com.hotel.dto.CreateTicketDTO;
import com.hotel.entity.ChatSession;
import com.hotel.entity.ServiceTicket;
import com.hotel.mapper.ChatSessionMapper;
import com.hotel.security.UserContext;
import com.hotel.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 工具：创建客房服务工单（送水/送六小件/清洁提醒等），流转到酒店前台处理。
 * <p>会话ID 由框架从 ToolContext 注入（ChatService 调用时放入 toolContext），
 * 避免依赖 LLM 猜测内部 ID；酒店ID 未提供时回退到会话绑定的酒店。
 */
@Component
@RequiredArgsConstructor
public class CreateTicketTool {

    private final TicketService ticketService;
    private final ChatSessionMapper sessionMapper;

    @Tool(description = "创建客房服务工单，如送水、送六小件（牙刷/拖鞋等）、清洁提醒。需提供请求类型，酒店ID可选（默认当前会话酒店）。")
    public String createTicket(CreateTicketRequest request, ToolContext toolContext) {
        Object sessionIdObj = toolContext == null ? null : toolContext.getContext().get("sessionId");
        if (sessionIdObj == null) {
            return "缺少会话上下文，无法创建工单";
        }
        Long sessionId = Long.valueOf(sessionIdObj.toString());
        Long hotelId = (request.hotelId() != null && request.hotelId() > 0)
                ? request.hotelId()
                : resolveSessionHotelId(sessionId);
        if (hotelId == null) {
            return "请提供酒店ID";
        }
        if (request.requestType() == null || request.requestType().isBlank()) {
            return "请说明需要什么服务（送水/送六小件/清洁提醒等）";
        }
        // 用户身份优先取 ToolContext：流式调用时工具可能运行在响应式线程上，ThreadLocal 为空
        Long userId = ToolUserContext.userId(toolContext);
        if (userId == null) {
            return "缺少登录用户信息，无法创建工单";
        }

        CreateTicketDTO dto = new CreateTicketDTO();
        dto.setSessionId(sessionId);
        dto.setHotelId(hotelId);
        dto.setRoomId(request.roomId());
        dto.setRequestType(request.requestType().trim());
        dto.setContent(request.content());
        try {
            ServiceTicket ticket = UserContext.runAs(userId, null, ToolUserContext.role(toolContext),
                    () -> ticketService.createTicket(dto));
            return "工单已创建成功！编号 " + ticket.getTicketNo()
                    + "，服务类型「" + ticket.getRequestType() + "」，状态：待处理，酒店前台会尽快为您安排，请留意房间服务。";
        } catch (BusinessException e) {
            return "创建工单失败：" + e.getMessage();
        }
    }

    /** 从会话记录中取绑定的酒店ID（会话创建时未指定酒店则为 null） */
    private Long resolveSessionHotelId(Long sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        return session == null ? null : session.getHotelId();
    }

    public record CreateTicketRequest(
            @ToolParam(description = "酒店ID，可选，不传则默认当前会话酒店", required = false) Long hotelId,
            @ToolParam(description = "请求类型：送水/送六小件/清洁提醒/其他") String requestType,
            @ToolParam(description = "房间号对应的房间ID，可选", required = false) Long roomId,
            @ToolParam(description = "需求描述，可选", required = false) String content) {
    }
}
