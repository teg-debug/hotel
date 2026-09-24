package com.hotel.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hotel.common.BusinessException;
import com.hotel.common.PageResult;
import com.hotel.dto.CreateTicketDTO;
import com.hotel.dto.TicketStatusDTO;
import com.hotel.entity.ServiceTicket;
import com.hotel.mapper.ServiceTicketMapper;
import com.hotel.security.UserContext;
import com.hotel.service.HotelScopeService;
import com.hotel.service.TicketService;
import com.hotel.utils.OrderNoGenerator;
import com.hotel.vo.ServiceTicketVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 服务工单实现：创建（AI/手动）、按角色隔离的列表、前台处理。
 *
 * <p>酒店维度的权限判定统一委托 {@link HotelScopeService}，保证与客服会话、
 * 知识库等模块使用同一套隔离口径。</p>
 */
@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    /** 允许的请求类型 */
    private static final Set<String> ALLOWED_TYPES = Set.of("送水", "送六小件", "清洁提醒", "其他");

    private final ServiceTicketMapper ticketMapper;
    private final HotelScopeService hotelScopeService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServiceTicket createTicket(CreateTicketDTO dto) {
        String type = dto.getRequestType() == null ? "其他" : dto.getRequestType();
        if (!ALLOWED_TYPES.contains(type)) {
            throw new BusinessException("暂不支持该服务类型，可选：送水/送六小件/清洁提醒/其他");
        }
        if (dto.getHotelId() == null) {
            throw new BusinessException("缺少酒店信息，无法创建工单");
        }
        ServiceTicket ticket = new ServiceTicket();
        ticket.setTicketNo(OrderNoGenerator.generate("TK", 6));
        ticket.setSessionId(dto.getSessionId());
        ticket.setUserId(UserContext.getUserId());
        ticket.setHotelId(dto.getHotelId());
        ticket.setRoomId(dto.getRoomId());
        ticket.setRequestType(type);
        ticket.setContent(dto.getContent());
        ticket.setPriority(dto.getPriority() == null ? 1 : dto.getPriority());
        ticket.setStatus(ServiceTicket.STATUS_PENDING);
        ticketMapper.insert(ticket);
        return ticket;
    }

    @Override
    public PageResult<ServiceTicketVO> pageTickets(Long hotelId, Integer status, int page, int size) {
        Integer role = UserContext.getRole();
        if (role == null || role < 1) {
            throw new BusinessException("无权限查看工单");
        }
        LambdaQueryWrapper<ServiceTicket> wrapper = new LambdaQueryWrapper<>();
        if (hotelId != null) {
            hotelScopeService.checkHotelAccess(hotelId, role);
            wrapper.eq(ServiceTicket::getHotelId, hotelId);
        } else {
            List<Long> hotelIds = hotelScopeService.allowedHotelIds(role);
            if (hotelIds != null) {
                // 非管理员且未指定酒店时，仅能看到权限范围内的酒店工单
                if (hotelIds.isEmpty()) {
                    return PageResult.of(0, 0, page, size, List.of());
                }
                wrapper.in(ServiceTicket::getHotelId, hotelIds);
            }
        }
        if (status != null) {
            wrapper.eq(ServiceTicket::getStatus, status);
        }
        wrapper.orderByDesc(ServiceTicket::getCreateTime);

        Page<ServiceTicket> p = ticketMapper.selectPage(new Page<>(page, size), wrapper);
        List<ServiceTicketVO> records = p.getRecords().stream().map(ServiceTicketVO::from).toList();
        return PageResult.of(p.getTotal(), p.getPages(), p.getCurrent(), p.getSize(), records);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServiceTicketVO updateStatus(Long id, TicketStatusDTO dto) {
        Integer role = UserContext.getRole();
        if (role == null || role < 1) {
            throw new BusinessException("无权限处理工单");
        }
        ServiceTicket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }
        hotelScopeService.checkHotelAccess(ticket.getHotelId(), role);

        // 状态机校验：只允许 待处理→处理中/已完成、处理中→已完成，已完成/已取消为终态
        Integer current = ticket.getStatus();
        Integer target = dto.getStatus();
        if (ServiceTicket.STATUS_CANCELED == current) {
            throw new BusinessException("工单已取消，无法再变更状态");
        }
        if (ServiceTicket.STATUS_DONE == current) {
            throw new BusinessException("工单已完成，无法再变更状态");
        }
        if (ServiceTicket.STATUS_PENDING == current
                && target != ServiceTicket.STATUS_PROCESSING && target != ServiceTicket.STATUS_DONE) {
            throw new BusinessException("待处理工单只能变更为处理中或已完成");
        }
        if (ServiceTicket.STATUS_PROCESSING == current && target != ServiceTicket.STATUS_DONE) {
            throw new BusinessException("处理中的工单只能变更为已完成");
        }
        // 完成工单必须留下处理结果：否则「已完成」既无处理人记录也无结论，
        // 事后无法追溯这次服务到底做没做
        if (ServiceTicket.STATUS_DONE == target
                && (dto.getHandleResult() == null || dto.getHandleResult().isBlank())) {
            throw new BusinessException("标记完成时必须填写处理结果");
        }

        ticket.setStatus(target);
        ticket.setAssigneeId(UserContext.getUserId());
        ticket.setHandleResult(dto.getHandleResult());
        if (target == ServiceTicket.STATUS_DONE) {
            ticket.setHandledTime(LocalDateTime.now());
        }
        ticketMapper.updateById(ticket);
        return ServiceTicketVO.from(ticket);
    }
}
