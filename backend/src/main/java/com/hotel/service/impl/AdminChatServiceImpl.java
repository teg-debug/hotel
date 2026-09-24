package com.hotel.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hotel.ai.push.UserPushPublisher;
import com.hotel.common.BusinessException;
import com.hotel.common.PageResult;
import com.hotel.dto.ChatSessionQueryDTO;
import com.hotel.entity.BookingOrder;
import com.hotel.entity.ChatMessage;
import com.hotel.entity.ChatSession;
import com.hotel.entity.User;
import com.hotel.mapper.BookingOrderMapper;
import com.hotel.mapper.ChatMessageMapper;
import com.hotel.mapper.ChatSessionMapper;
import com.hotel.mapper.UserMapper;
import com.hotel.security.UserContext;
import com.hotel.service.AdminChatService;
import com.hotel.service.HotelScopeService;
import com.hotel.vo.ChatMessageVO;
import com.hotel.vo.ChatReplyVO;
import com.hotel.vo.ChatSessionAdminVO;
import com.hotel.vo.ChatSessionDetailVO;
import com.hotel.vo.HotQuestionVO;
import com.hotel.vo.ResolutionStats;
import com.hotel.vo.TrendPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理端客服实现：会话列表/详情、人工回复、标签、统计。
 *
 * <p>所有会话访问都经过 {@link HotelScopeService} 的酒店维度校验：
 * 经营者与前台只能看到自己酒店的会话，平台级会话（无酒店归属）仅系统管理员可见。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminChatServiceImpl implements AdminChatService {

    /** 允许的会话标签 */
    private static final Set<String> ALLOWED_TAGS = Set.of("已解决", "转技术", "投诉");

    /** 判定会话已解决并终结会话的标签 */
    private static final String RESOLVED_TAG = "已解决";

    /** 热问榜统计窗口（天） */
    private static final int HOT_QUESTION_DAYS = 30;

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final UserMapper userMapper;
    private final BookingOrderMapper orderMapper;
    private final ChatMemory chatMemory;
    private final UserPushPublisher userPushPublisher;
    private final HotelScopeService hotelScopeService;

    @Override
    public PageResult<ChatSessionAdminVO> pageSessions(ChatSessionQueryDTO dto) {
        Integer role = UserContext.getRole();
        if (role == null || role < 1) {
            throw new BusinessException("无权限查看客服会话");
        }
        LambdaQueryWrapper<ChatSession> wrapper = new LambdaQueryWrapper<>();
        List<Long> hotelIds = hotelScopeService.allowedHotelIds(role);
        if (hotelIds != null) {
            if (hotelIds.isEmpty()) {
                // 名下无酒店：直接返回空，避免退化成全量查询
                return PageResult.of(0, 0, dto.getPage(), dto.getSize(), List.of());
            }
            wrapper.in(ChatSession::getHotelId, hotelIds);
        }
        if (dto.getStatus() != null) {
            wrapper.eq(ChatSession::getStatus, dto.getStatus());
        }
        if (dto.getTransferFlag() != null) {
            wrapper.eq(ChatSession::getTransferFlag, dto.getTransferFlag());
        }
        if (dto.getRating() != null) {
            wrapper.eq(ChatSession::getRating, dto.getRating());
        }
        if (dto.getStartDate() != null) {
            wrapper.ge(ChatSession::getStartTime, dto.getStartDate().atStartOfDay());
        }
        if (dto.getEndDate() != null) {
            wrapper.le(ChatSession::getStartTime, dto.getEndDate().atTime(LocalTime.MAX));
        }
        wrapper.orderByDesc(ChatSession::getId);

        Page<ChatSession> page = sessionMapper.selectPage(new Page<>(dto.getPage(), dto.getSize()), wrapper);
        List<ChatSession> sessions = page.getRecords();

        // 批量装配用户信息与订单数
        Map<Long, User> userMap = loadUsers(sessions.stream().map(ChatSession::getUserId).toList());
        Map<Long, Long> orderCountMap = loadOrderCounts(sessions.stream().map(ChatSession::getUserId).toList());

        List<ChatSessionAdminVO> records = sessions.stream().map(s -> {
            ChatSessionAdminVO vo = ChatSessionAdminVO.from(s);
            User u = userMap.get(s.getUserId());
            if (u != null) {
                vo.setUsername(u.getUsername());
                vo.setMemberLevel(u.getMemberLevel());
            }
            vo.setOrderCount(orderCountMap.getOrDefault(s.getUserId(), 0L));
            return vo;
        }).toList();
        return PageResult.of(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(), records);
    }

    @Override
    public ChatSessionDetailVO getSessionDetail(Long sessionId) {
        ChatSession session = getScopedSession(sessionId);
        ChatSessionDetailVO detail = new ChatSessionDetailVO();
        detail.setSession(ChatSessionAdminVO.from(session));
        User user = userMapper.selectById(session.getUserId());
        if (user != null) {
            detail.setUsername(user.getUsername());
            detail.setMemberLevel(user.getMemberLevel());
        }
        detail.setOrderCount(orderMapper.selectCount(
                new LambdaQueryWrapper<BookingOrder>().eq(BookingOrder::getUserId, session.getUserId())));
        detail.setMessages(messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getId))
                .stream().map(ChatMessageVO::from).toList());
        return detail;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reply(Long sessionId, String content) {
        ChatSession session = getScopedSession(sessionId);

        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setSender(ChatMessage.SENDER_HUMAN);
        message.setContent(content);
        message.setMsgType(0);
        message.setIntentTag("人工客服");
        message.setCreateTime(LocalDateTime.now());
        messageMapper.insert(message);

        // 按字段更新：消息计数用自增表达式，避免并发下用旧值覆盖造成计数丢失
        LambdaUpdateWrapper<ChatSession> update = new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId)
                .set(ChatSession::getStaffUserId, UserContext.getUserId())
                .setSql("message_count = message_count + 1");
        if (session.getStatus() == ChatSession.STATUS_ACTIVE || session.getStatus() == ChatSession.STATUS_ENDED) {
            update.set(ChatSession::getStatus, ChatSession.STATUS_TRANSFERRED)
                    .set(ChatSession::getTransferFlag, 1)
                    .set(ChatSession::getTransferReason, "客服人工介入");
        }
        sessionMapper.update(null, update);

        // 推送到该用户自己的点对点队列（不再使用可被任意订阅的广播主题）；
        // 经 Redis 跨实例扇出：用户连在哪个实例上不确定，本机直接投递会在收件人不在本机时丢弃
        userPushPublisher.push(String.valueOf(session.getUserId()), "/queue/chat",
                new ChatReplyVO(sessionId, content, "人工客服", 1.0, false, null, "human"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void tag(Long sessionId, String tag) {
        if (tag == null || !ALLOWED_TAGS.contains(tag)) {
            throw new BusinessException("标签仅支持：已解决/转技术/投诉");
        }
        getScopedSession(sessionId);
        LambdaUpdateWrapper<ChatSession> update = new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId)
                .set(ChatSession::getTag, tag);
        boolean resolved = RESOLVED_TAG.equals(tag);
        if (resolved) {
            update.set(ChatSession::getStatus, ChatSession.STATUS_ENDED)
                    .set(ChatSession::getEndTime, LocalDateTime.now());
        }
        sessionMapper.update(null, update);
        if (resolved) {
            // 会话判定为已解决：多轮上下文不再需要，立即回收，不必等 TTL 自然过期
            clearMemory(sessionId);
        }
    }

    /** 清理该会话的多轮上下文；清理失败不影响标签与状态流转，TTL 仍会兜底 */
    private void clearMemory(Long sessionId) {
        try {
            chatMemory.clear(String.valueOf(sessionId));
        } catch (Exception e) {
            log.warn("清理会话记忆失败 sessionId={}", sessionId, e);
        }
    }

    @Override
    public List<TrendPoint> trend(int days) {
        int n = Math.min(Math.max(days, 1), 90);
        LocalDate today = LocalDate.now();
        Map<String, Long> countMap = sessionMapper.countByDate(today.minusDays(n - 1L).atStartOfDay()).stream()
                .collect(Collectors.toMap(
                        m -> String.valueOf(m.get("date")),
                        m -> Long.valueOf(m.get("cnt").toString())));
        List<TrendPoint> points = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        for (int i = n - 1; i >= 0; i--) {
            String date = today.minusDays(i).format(fmt);
            points.add(new TrendPoint(date, countMap.getOrDefault(date, 0L)));
        }
        return points;
    }

    /**
     * 解决率统计。
     *
     * <p>口径说明：{@code transferred} 与 {@code active} 互斥（转人工的会话状态一定不再是进行中），
     * 因此「AI 独立解决」= 未转人工且已脱离进行中的会话，等于总数减去转人工数再减去进行中数。
     * 这样三个指标都落在同一分母内，不会出现同一会话被重复计入分子分母的情况。</p>
     */
    @Override
    public ResolutionStats resolution() {
        long total = sessionMapper.selectCount(null);
        long active = sessionMapper.selectCount(
                new LambdaQueryWrapper<ChatSession>().eq(ChatSession::getStatus, ChatSession.STATUS_ACTIVE));
        long transferred = sessionMapper.selectCount(
                new LambdaQueryWrapper<ChatSession>().eq(ChatSession::getTransferFlag, 1));
        long aiResolved = total - transferred - active;

        ResolutionStats stats = new ResolutionStats();
        stats.setTotal(total);
        stats.setActive(active);
        stats.setTransferred(transferred);
        stats.setAiResolved(Math.max(0, aiResolved));
        return stats;
    }

    /**
     * 热问榜：统计用户近 30 天真实提出的问题。
     *
     * <p>原实现按知识条目的 hit_count 排序，只能反映「已有知识的被用次数」，
     * 新出现但尚无对应知识的问题反而统计不到。改为按提问原文聚合后，
     * 转人工次数偏高的条目就是待补充的知识缺口。</p>
     *
     * <p>与其它客服接口一致，按酒店维度过滤，避免经营者看到其它酒店的咨询内容。</p>
     */
    @Override
    public List<HotQuestionVO> hotQuestions(int limit) {
        Integer role = UserContext.getRole();
        if (role == null || role < 1) {
            throw new BusinessException("无权限查看客服统计");
        }
        List<Long> hotelIds = hotelScopeService.allowedHotelIds(role);
        if (hotelIds != null && hotelIds.isEmpty()) {
            return List.of();
        }
        return messageMapper.topUserQuestions(
                LocalDateTime.now().minusDays(HOT_QUESTION_DAYS),
                Math.min(Math.max(limit, 1), 50),
                hotelIds);
    }

    // ==================== 私有工具 ====================

    /** 读取会话并校验酒店维度访问权限 */
    private ChatSession getScopedSession(Long sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("会话不存在");
        }
        hotelScopeService.checkSessionAccess(session.getHotelId(), UserContext.getRole());
        return session;
    }

    private Map<Long, User> loadUsers(List<Long> userIds) {
        Set<Long> ids = new HashSet<>(userIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private Map<Long, Long> loadOrderCounts(List<Long> userIds) {
        Set<Long> ids = new HashSet<>(userIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        orderMapper.selectList(new LambdaQueryWrapper<BookingOrder>()
                        .select(BookingOrder::getUserId)
                        .in(BookingOrder::getUserId, ids))
                .forEach(o -> counts.merge(o.getUserId(), 1L, Long::sum));
        return counts;
    }
}
