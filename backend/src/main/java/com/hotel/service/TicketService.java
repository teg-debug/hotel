package com.hotel.service;

import com.hotel.common.PageResult;
import com.hotel.dto.CreateTicketDTO;
import com.hotel.dto.TicketStatusDTO;
import com.hotel.entity.ServiceTicket;
import com.hotel.vo.ServiceTicketVO;

/**
 * 服务工单：AI 创建 / 前台处理 / 列表查询
 */
public interface TicketService {

    /** 创建工单（AI 工具或用户手动触发） */
    ServiceTicket createTicket(CreateTicketDTO dto);

    /** 分页查询工单（前台/经营者/管理员，按角色隔离酒店范围） */
    PageResult<ServiceTicketVO> pageTickets(Long hotelId, Integer status, int page, int size);

    /** 更新工单状态（处理中/已完成），仅限本酒店处理人 */
    ServiceTicketVO updateStatus(Long id, TicketStatusDTO dto);
}
