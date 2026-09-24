package com.hotel.service;

import com.hotel.common.PageResult;
import com.hotel.dto.ChatSessionQueryDTO;
import com.hotel.vo.ChatSessionAdminVO;
import com.hotel.vo.ChatSessionDetailVO;
import com.hotel.vo.HotQuestionVO;
import com.hotel.vo.ResolutionStats;
import com.hotel.vo.TrendPoint;

import java.util.List;

/**
 * 管理端客服：会话列表 / 人工回复 / 会话标签 / 数据统计
 */
public interface AdminChatService {

    /** 会话列表（筛选 + 分页，含用户信息） */
    PageResult<ChatSessionAdminVO> pageSessions(ChatSessionQueryDTO dto);

    /** 会话详情（消息记录 + 用户信息） */
    ChatSessionDetailVO getSessionDetail(Long sessionId);

    /** 人工回复（发送者=客服，并广播给用户端 WebSocket） */
    void reply(Long sessionId, String content);

    /** 会话标签：已解决/转技术/投诉 */
    void tag(Long sessionId, String tag);

    /** 近 N 日对话量趋势（缺失日期补 0） */
    List<TrendPoint> trend(int days);

    /** AI 解决率 / 转人工率 / 进行中统计 */
    ResolutionStats resolution();

    /** 高频问题 Top N（按知识库命中次数） */
    List<HotQuestionVO> hotQuestions(int limit);
}
