package com.hotel.service;

import com.hotel.common.PageResult;
import com.hotel.dto.ChatStartDTO;
import com.hotel.dto.RateDTO;
import com.hotel.vo.ChatMessageVO;
import com.hotel.vo.ChatReplyVO;
import com.hotel.vo.ChatSessionVO;

import java.util.List;

/**
 * 智能客服对话：会话管理 + 意图识别 + RAG + LLM 生成 + 转人工
 */
public interface ChatService {

    /** 创建新会话 */
    ChatSessionVO startSession(ChatStartDTO dto);

    /** 发送消息（同步返回完整回答） */
    ChatReplyVO sendMessage(Long sessionId, String content);

    /**
     * 流式发送消息（SSE）：每个文本分片回调 {@code onChunk}，返回值是完整结果。
     *
     * <p>调用方需要先在当前线程调用 {@link #assertSendable} 做同步校验，
     * 否则业务错误只能在响应头提交后以事件形式返回。</p>
     */
    ChatReplyVO streamMessage(Long sessionId, String content, java.util.function.Consumer<String> onChunk);

    /**
     * 校验会话当前是否可发送消息：内容非空且不超长、会话归属当前用户、会话未结束。
     *
     * @throws com.hotel.common.BusinessException 校验不通过
     */
    void assertSendable(Long sessionId, String content);

    /** 会话历史记录 */
    List<ChatMessageVO> history(Long sessionId);

    /** 转人工客服 */
    ChatSessionVO transfer(Long sessionId, String reason);

    /** 满意度评价 */
    ChatSessionVO rate(Long sessionId, RateDTO dto);

    /** 我的会话列表 */
    PageResult<ChatSessionVO> pageMySessions(int page, int size);

    /**
     * 回收长时间无消息往来的会话（进行中与已转人工都回收）。
     *
     * @param cutoff 活性早于该时间的会话视为闲置
     * @param limit  单次最多回收数量
     * @return 实际回收的会话数
     */
    int reclaimIdleSessions(java.time.LocalDateTime cutoff, int limit);
}
