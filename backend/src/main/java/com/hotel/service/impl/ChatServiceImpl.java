package com.hotel.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hotel.ai.IntentClassifier;
import com.hotel.ai.ToolInvocationRecorder;
import com.hotel.ai.push.UserPushPublisher;
import com.hotel.common.BusinessException;
import com.hotel.common.PageResult;
import com.hotel.dto.ChatStartDTO;
import com.hotel.dto.RateDTO;
import com.hotel.entity.ChatMessage;
import com.hotel.entity.ChatSession;
import com.hotel.entity.Hotel;
import com.hotel.mapper.ChatMessageMapper;
import com.hotel.mapper.ChatSessionMapper;
import com.hotel.mapper.HotelMapper;
import com.hotel.mapper.UserMapper;
import com.hotel.security.UserContext;
import com.hotel.service.ChatService;
import com.hotel.service.KnowledgeService;
import com.hotel.utils.OrderNoGenerator;
import com.hotel.vo.ChatMessageVO;
import com.hotel.vo.ChatReplyVO;
import com.hotel.vo.ChatSessionVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 智能客服核心流水线：
 * <pre>
 * 用户消息 → 规则意图识别（含出范围闸门）
 *   → 已转人工：转发给坐席，不再走 AI
 *   → 知识库类意图：RAG 检索，命中直接返回；未命中时明确告知模型「知识库无结果」
 *   → 其余：ChatClient（多轮记忆 + Function Calling 工具）生成
 *   → 出范围 / AI 不可用：转人工（状态迁移 + 点对点通知坐席）
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    /** 单条消息长度上限 */
    private static final int MAX_CONTENT_LENGTH = 500;
    private static final String CHAT_QUEUE = "/queue/chat";
    private static final String STAFF_QUEUE = "/queue/staff";

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final HotelMapper hotelMapper;
    private final UserMapper userMapper;
    private final IntentClassifier intentClassifier;
    private final KnowledgeService knowledgeService;
    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final UserPushPublisher userPushPublisher;

    @Override
    public ChatSessionVO startSession(ChatStartDTO dto) {
        ChatSession session = new ChatSession();
        session.setSessionNo(OrderNoGenerator.generate("CHAT", 6));
        session.setUserId(UserContext.getUserId());
        session.setHotelId(dto.getHotelId());
        session.setSource(dto.getSource() == null ? 0 : dto.getSource());
        session.setStatus(ChatSession.STATUS_ACTIVE);
        session.setTransferFlag(0);
        session.setMessageCount(0);
        session.setStartTime(LocalDateTime.now());
        sessionMapper.insert(session);
        return ChatSessionVO.from(session);
    }

    @Override
    public ChatReplyVO sendMessage(Long sessionId, String content) {
        ChatSession session = requireSendable(sessionId, content);
        Integer status = session.getStatus();

        // 1. 规则意图识别（含出范围检测）
        IntentClassifier.IntentResult intent = intentClassifier.classify(content);
        saveMessage(sessionId, ChatMessage.SENDER_USER, content, intent.intent(), null);

        // 2. 已转人工：人工坐席已接管，用户消息直接转发给坐席，不再触发 AI
        if (status != null && status == ChatSession.STATUS_TRANSFERRED) {
            notifyStaff(session, content, "用户追问");
            return new ChatReplyVO(sessionId, "消息已送达人工客服，请稍候。",
                    intent.intent(), 1.0, true, null, "human");
        }

        // 3. 出范围意图 → 直接转人工
        if (intent.outOfScope()) {
            return doTransfer(session, intent.reason(), "已为您转接人工客服。" + intent.reason());
        }

        // 4. 知识库类意图：RAG 检索，命中直接返回（快且准）
        //    其他意图不检索知识库，避免无关问题被误答
        KnowledgeService.KnowledgeHit hit = null;
        boolean knowledgeIntent = IntentClassifier.isKnowledgeIntent(intent.intent());
        if (knowledgeIntent) {
            hit = knowledgeService.search(content, session.getHotelId());
        }
        if (hit != null) {
            String answer = hit.knowledge().getAnswer();
            saveMessage(sessionId, ChatMessage.SENDER_AI, answer, intent.intent(), null);
            log.info("知识库直答命中 sessionId={} knowledgeId={} 置信度={}",
                    sessionId, hit.knowledge().getId(), String.format("%.3f", hit.confidence()));
            return new ChatReplyVO(sessionId, answer, intent.intent(), hit.confidence(),
                    false, null, "knowledge");
        }

        // 5. LLM 生成：多轮记忆（会话ID维度）+ 工具调用（Function Calling）
        LlmResult llmResult = callModel(session, content, sessionId, knowledgeIntent);
        if (llmResult == null) {
            return doTransfer(session, "AI 服务暂时不可用", "抱歉，AI 服务暂时不可用，已为您转接人工客服，请稍候。");
        }
        saveMessage(sessionId, ChatMessage.SENDER_AI, llmResult.answer(), intent.intent(),
                llmResult.toolName(), llmResult.tokenCount());
        return new ChatReplyVO(sessionId, llmResult.answer(), intent.intent(), 1.0, false, null, "llm");
    }

    /**
     * 流式发送消息：把回答分片交给 {@code onChunk}，同时返回完整结果。
     *
     * <p>转人工与知识库直答这两条路径不经过模型，因此只会推送一个完整分片；
     * 只有需要模型生成的场景才是真正的逐字流式。</p>
     */
    @Override
    public ChatReplyVO streamMessage(Long sessionId, String content, Consumer<String> onChunk) {
        ChatSession session = requireSendable(sessionId, content);
        Integer status = session.getStatus();

        IntentClassifier.IntentResult intent = intentClassifier.classify(content);
        saveMessage(sessionId, ChatMessage.SENDER_USER, content, intent.intent(), null);

        // 已转人工：人工坐席已接管，用户消息直接转发，不再触发 AI
        if (status != null && status == ChatSession.STATUS_TRANSFERRED) {
            notifyStaff(session, content, "用户追问");
            String reply = "消息已送达人工客服，请稍候。";
            onChunk.accept(reply);
            return new ChatReplyVO(sessionId, reply, intent.intent(), 1.0, true, null, "human");
        }

        // 出范围意图 → 转人工
        if (intent.outOfScope()) {
            ChatReplyVO transferred = doTransfer(session, intent.reason(),
                    "已为您转接人工客服。" + intent.reason());
            onChunk.accept(transferred.getReply());
            return transferred;
        }

        // 知识库类意图：命中则直接返回原文，比模型生成更快也更可靠
        KnowledgeService.KnowledgeHit hit = null;
        boolean knowledgeIntent = IntentClassifier.isKnowledgeIntent(intent.intent());
        if (knowledgeIntent) {
            hit = knowledgeService.search(content, session.getHotelId());
        }
        if (hit != null) {
            String answer = hit.knowledge().getAnswer();
            saveMessage(sessionId, ChatMessage.SENDER_AI, answer, intent.intent(), null);
            log.info("知识库直答命中 sessionId={} knowledgeId={} 置信度={}",
                    sessionId, hit.knowledge().getId(), String.format("%.3f", hit.confidence()));
            onChunk.accept(answer);
            return new ChatReplyVO(sessionId, answer, intent.intent(), hit.confidence(),
                    false, null, "knowledge");
        }
        return streamFromModel(session, content, sessionId, knowledgeIntent, intent, onChunk);
    }

    @Override
    public List<ChatMessageVO> history(Long sessionId) {
        getOwnedSession(sessionId);
        return messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getId))
                .stream().map(ChatMessageVO::from).toList();
    }

    @Override
    public ChatSessionVO transfer(Long sessionId, String reason) {
        ChatSession session = getOwnedSession(sessionId);
        if (session.getStatus() != null && session.getStatus() == ChatSession.STATUS_TRANSFERRED) {
            throw new BusinessException("会话已转人工");
        }
        String finalReason = (reason == null || reason.isBlank()) ? "用户要求转接人工客服" : reason;
        doTransfer(session, finalReason, "已为您转接人工客服，请稍候。");
        session.setStatus(ChatSession.STATUS_TRANSFERRED);
        session.setTransferFlag(1);
        session.setTransferReason(finalReason);
        return ChatSessionVO.from(session);
    }

    @Override
    public ChatSessionVO rate(Long sessionId, RateDTO dto) {
        ChatSession session = getOwnedSession(sessionId);
        // 只更新评价相关字段，避免用旧快照覆盖并发写入的状态与计数字段
        LambdaUpdateWrapper<ChatSession> update = new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId)
                .set(ChatSession::getRating, dto.getRating())
                .set(ChatSession::getRatingComment, dto.getComment());
        boolean ended = session.getStatus() != null && session.getStatus() == ChatSession.STATUS_ACTIVE;
        if (ended) {
            update.set(ChatSession::getStatus, ChatSession.STATUS_ENDED)
                    .set(ChatSession::getEndTime, LocalDateTime.now());
        }
        sessionMapper.update(null, update);
        if (ended) {
            // 会话已终结：除了 TTL 自然过期，再做一次即时清理，避免评价后的会话继续占用上下文
            clearMemory(sessionId);
        }
        session.setRating(dto.getRating());
        session.setRatingComment(dto.getComment());
        return ChatSessionVO.from(session);
    }

    @Override
    public PageResult<ChatSessionVO> pageMySessions(int page, int size) {
        Page<ChatSession> p = sessionMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getUserId, UserContext.getUserId())
                        .orderByDesc(ChatSession::getId));
        List<ChatSessionVO> records = p.getRecords().stream().map(ChatSessionVO::from).toList();
        return PageResult.of(p.getTotal(), p.getPages(), p.getCurrent(), p.getSize(), records);
    }

    /**
     * 回收闲置会话：把长时间没有消息往来的「进行中」与「已转人工」会话置为超时回收。
     *
     * <p>已转人工的会话也要回收，否则无人接手的会话会一直留在坐席的待办列表里。
     * 更新带旧状态条件，避免覆盖并发写入的终态（例如用户刚好在回收前完成了评价）。</p>
     */
    @Override
    public int reclaimIdleSessions(LocalDateTime cutoff, int limit) {
        List<Long> idleSessionIds = sessionMapper.selectIdleSessionIds(cutoff, limit);
        if (idleSessionIds.isEmpty()) {
            return 0;
        }
        int reclaimed = 0;
        for (Long sessionId : idleSessionIds) {
            int updated = sessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>()
                    .eq(ChatSession::getId, sessionId)
                    .in(ChatSession::getStatus, ChatSession.STATUS_ACTIVE, ChatSession.STATUS_TRANSFERRED)
                    .set(ChatSession::getStatus, ChatSession.STATUS_EXPIRED)
                    .set(ChatSession::getEndTime, LocalDateTime.now()));
            if (updated > 0) {
                reclaimed++;
                clearMemory(sessionId);
            }
        }
        return reclaimed;
    }

    /** 清理该会话的多轮上下文；清理失败不影响会话状态流转，TTL 仍会兜底 */
    private void clearMemory(Long sessionId) {
        try {
            chatMemory.clear(String.valueOf(sessionId));
        } catch (Exception e) {
            log.warn("清理会话记忆失败 sessionId={}", sessionId, e);
        }
    }

    // ==================== 私有方法 ====================

    @Override
    public void assertSendable(Long sessionId, String content) {
        requireSendable(sessionId, content);
    }

    /** 校验并返回会话：内容非空、不超长、归属当前用户、会话未结束 */
    private ChatSession requireSendable(Long sessionId, String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException("消息内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException("单条消息不能超过 " + MAX_CONTENT_LENGTH + " 个字");
        }
        ChatSession session = getOwnedSession(sessionId);
        Integer status = session.getStatus();
        if (status != null && (status == ChatSession.STATUS_ENDED || status == ChatSession.STATUS_EXPIRED)) {
            throw new BusinessException("会话已结束，请重新发起咨询");
        }
        return session;
    }

    /** 一次模型调用的结果：回答文本、本次触发的工具名、token 消耗 */
    private record LlmResult(String answer, String toolName, Integer tokenCount) {
    }

    /**
     * 同步调用模型（含多轮记忆与工具调用）。
     *
     * @return 调用结果；模型不可用时返回 null，由调用方决定如何降级
     */
    private LlmResult callModel(ChatSession session, String content, Long sessionId, boolean knowledgeIntent) {
        long startedAt = System.currentTimeMillis();
        ToolInvocationRecorder.begin(String.valueOf(sessionId));
        ChatResponse response;
        try {
            response = chatClient.prompt()
                    .system(buildSystemPrompt(session, knowledgeIntent))
                    .user(content)
                    .toolContext(buildToolContext(session, sessionId))
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, String.valueOf(sessionId)))
                    .call()
                    .chatResponse();
        } catch (Exception e) {
            ToolInvocationRecorder.finish(String.valueOf(sessionId));
            log.error("LLM 调用失败 sessionId={} 耗时={}ms",
                    sessionId, System.currentTimeMillis() - startedAt, e);
            return null;
        }
        String toolName = ToolInvocationRecorder.finish(String.valueOf(sessionId));
        Integer tokenCount = extractTokenCount(response);
        String answer = extractText(response);
        if (answer == null || answer.isBlank()) {
            answer = "抱歉，我没有完全理解您的意思，您可以换个说法，或让我为您转接人工客服。";
        }
        log.info("LLM 回复完成 sessionId={} 工具={} tokens={} 耗时={}ms",
                sessionId, toolName == null ? "无" : toolName, tokenCount,
                System.currentTimeMillis() - startedAt);
        return new LlmResult(answer, toolName, tokenCount);
    }

    /**
     * 流式调用模型，逐片回调并把完整回答落库。
     *
     * <p>工具上下文在此显式构建并交给框架：流式调用中工具可能执行在响应式线程上，
     * 那里读不到 ThreadLocal 里的 UserContext，必须由 ToolContext 传递用户身份。</p>
     */
    private ChatReplyVO streamFromModel(ChatSession session, String content, Long sessionId,
                                        boolean knowledgeIntent, IntentClassifier.IntentResult intent,
                                        Consumer<String> onChunk) {
        Map<String, Object> toolContext = buildToolContext(session, sessionId);
        StringBuilder answer = new StringBuilder();
        // 流式响应只有最后一帧带用量（供应商在末尾汇总），因此逐帧覆盖、以最后一帧为准
        AtomicReference<Integer> tokenCount = new AtomicReference<>();
        long startedAt = System.currentTimeMillis();
        ToolInvocationRecorder.begin(String.valueOf(sessionId));
        try {
            chatClient.prompt()
                    .system(buildSystemPrompt(session, knowledgeIntent))
                    .user(content)
                    .toolContext(toolContext)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, String.valueOf(sessionId)))
                    .stream()
                    .chatResponse()
                    .doOnNext(frame -> {
                        String delta = extractText(frame);
                        if (delta != null && !delta.isEmpty()) {
                            answer.append(delta);
                            onChunk.accept(delta);
                        }
                        Integer usage = extractTokenCount(frame);
                        if (usage != null) {
                            tokenCount.set(usage);
                        }
                    })
                    .blockLast();
        } catch (Exception e) {
            ToolInvocationRecorder.finish(String.valueOf(sessionId));
            log.error("流式 LLM 调用失败 sessionId={} 耗时={}ms",
                    sessionId, System.currentTimeMillis() - startedAt, e);
            ChatReplyVO fallback = doTransfer(session, "AI 服务暂时不可用",
                    "抱歉，AI 服务暂时不可用，已为您转接人工客服，请稍候。");
            onChunk.accept(fallback.getReply());
            return fallback;
        }
        String toolName = ToolInvocationRecorder.finish(String.valueOf(sessionId));
        String text = answer.toString();
        if (text.isBlank()) {
            text = "抱歉，我没有完全理解您的意思，您可以换个说法，或让我为您转接人工客服。";
            onChunk.accept(text);
        }
        saveMessage(sessionId, ChatMessage.SENDER_AI, text, intent.intent(), toolName, tokenCount.get());
        log.info("流式回复完成 sessionId={} 字符数={} 工具={} tokens={} 耗时={}ms",
                sessionId, text.length(), toolName == null ? "无" : toolName, tokenCount.get(),
                System.currentTimeMillis() - startedAt);
        return new ChatReplyVO(sessionId, text, intent.intent(), 1.0, false, null, "llm");
    }

    /**
     * 工具上下文：会话ID、酒店ID 与当前用户身份。
     *
     * <p>用户身份必须显式传入，不能只依赖 ThreadLocal：同步调用时拦截器已写入上下文，
     * 但流式调用中工具可能运行在响应式线程上，那时 {@code UserContext} 是空的，
     * 工具内部的订单查询与下单会因取不到用户而失败。</p>
     */
    private Map<String, Object> buildToolContext(ChatSession session, Long sessionId) {
        Map<String, Object> context = new HashMap<>();
        context.put("sessionId", String.valueOf(sessionId));
        Long userId = UserContext.getUserId();
        if (userId != null) {
            context.put("userId", String.valueOf(userId));
        }
        Integer role = UserContext.getRole();
        if (role != null) {
            context.put("role", String.valueOf(role));
        }
        if (session != null && session.getHotelId() != null) {
            context.put("hotelId", String.valueOf(session.getHotelId()));
        }
        return context;
    }

    /** 取模型回答文本；响应结构异常时返回 null，由调用方走兜底话术 */
    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    /** 取本次调用的 token 消耗；供应商未返回用量时返回 null */
    private Integer extractTokenCount(ChatResponse response) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return null;
        }
        return response.getMetadata().getUsage().getTotalTokens();
    }


    /** 转人工：会话状态迁移 + 保存提示消息 + 通知该酒店的前台与经营者 */
    private ChatReplyVO doTransfer(ChatSession session, String reason, String reply) {
        sessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getId, session.getId())
                .set(ChatSession::getStatus, ChatSession.STATUS_TRANSFERRED)
                .set(ChatSession::getTransferFlag, 1)
                .set(ChatSession::getTransferReason, reason));
        saveMessage(session.getId(), ChatMessage.SENDER_AI, reply, IntentClassifier.INTENT_OUT_OF_SCOPE, null);
        notifyStaff(session, reason, "转人工");
        log.info("会话转人工 sessionId={} hotelId={} 原因={}",
                session.getId(), session.getHotelId(), reason);
        return new ChatReplyVO(session.getId(), reply, IntentClassifier.INTENT_OUT_OF_SCOPE,
                0.0, true, reason, "transfer");
    }

    /**
     * 通知该酒店的前台与经营者。
     *
     * <p>投递到收件人自己的点对点队列，而不是广播主题：
     * 广播主题无法按归属授权，任何登录用户订阅即可收到全平台的转人工事件。</p>
     *
     * <p>投递经 {@link UserPushPublisher} 走 Redis 跨实例扇出：坐席连在哪个实例上不确定，
     * 直接调本机模板只会在本实例的连接注册表里找收件人，找不到就静默丢弃。</p>
     */
    private void notifyStaff(ChatSession session, String reason, String type) {
        if (session.getHotelId() == null) {
            log.warn("平台级会话转人工，暂无归属酒店可通知 sessionId={}", session.getId());
            return;
        }
        List<Long> notifierIds = userMapper.selectHotelNotifierIds(session.getHotelId());
        if (notifierIds == null || notifierIds.isEmpty()) {
            log.warn("酒店无可通知的前台或经营者 hotelId={} sessionId={}",
                    session.getHotelId(), session.getId());
            return;
        }
        Map<String, Object> payload = Map.of(
                "type", type,
                "sessionId", session.getId(),
                "hotelId", session.getHotelId(),
                "reason", reason == null ? "" : reason);
        for (Long notifierId : notifierIds) {
            userPushPublisher.push(String.valueOf(notifierId), STAFF_QUEUE, payload);
        }
    }

    /** 会话归属校验：仅本人可访问 */
    private ChatSession getOwnedSession(Long sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(UserContext.getUserId())) {
            throw new BusinessException("会话不存在或无权访问");
        }
        return session;
    }

    /** 保存消息并原子自增会话消息数，避免读改写造成计数丢失 */
    private void saveMessage(Long sessionId, int sender, String content, String intent, String toolName) {
        saveMessage(sessionId, sender, content, intent, toolName, null);
    }

    /**
     * 保存消息（含工具名与 token 消耗），并原子自增会话消息数。
     *
     * <p>{@code tool_name} 与 {@code token_count} 是对话可观测性的两个关键字段：
     * 前者说明回答是靠哪个工具拿到的，后者用于估算成本。两者此前从未写入。</p>
     */
    private void saveMessage(Long sessionId, int sender, String content, String intent,
                             String toolName, Integer tokenCount) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setSender(sender);
        message.setContent(content);
        message.setMsgType(0);
        message.setIntentTag(intent);
        message.setToolName(toolName);
        message.setTokenCount(tokenCount);
        message.setCreateTime(LocalDateTime.now());
        messageMapper.insert(message);

        sessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getId, sessionId)
                .setSql("message_count = message_count + 1"));
    }

    /**
     * 系统提示词：角色 + 安全规则 + 酒店上下文。
     *
     * @param knowledgeIntent 本次是否走了知识库检索；为 true 且没有命中时，
     *                        明确告知模型「知识库无结果」，避免它在没有依据的情况下自行编造
     */
    private String buildSystemPrompt(ChatSession session, boolean knowledgeIntent) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是酒店智能前台助手，24小时在线解答住客咨询与处理服务请求。\n")
                .append("行为规则：\n")
                .append("1. 回答必须基于工具返回的实时数据或对话中已给出的明确信息，禁止编造房态、价格、订单信息。\n")
                .append("2. 查房态/下单/查订单/建工单/查酒店信息等操作一律调用对应工具完成，不要凭记忆作答。\n")
                .append("3. 涉及退款、投诉、隐私、医疗、法律等话题，礼貌告知将转接人工客服，不要自行承诺。\n")
                .append("4. 语言简洁友好；无论用户用什么语言提问，都必须用中文回答。\n");
        if (knowledgeIntent) {
            sb.append("5. 本次已检索酒店知识库但未找到匹配内容，请如实告知没有查到相关资料，")
                    .append("并建议用户换个说法或转接人工客服，不要自行推测答案。\n");
        }
        if (session.getHotelId() != null) {
            Hotel hotel = hotelMapper.selectById(session.getHotelId());
            if (hotel != null) {
                // 把酒店编号一并交给模型，否则需要 hotelId 参数的查询工具无法被正确调用
                sb.append("当前咨询酒店：").append(hotel.getName())
                        .append("（酒店编号 hotelId=").append(hotel.getId())
                        .append("，").append(hotel.getCity())
                        .append("，").append(hotel.getStarLevel()).append("星）\n");
                if (hotel.getCheckinTime() != null && hotel.getCheckoutTime() != null) {
                    sb.append("入住时间 ").append(hotel.getCheckinTime())
                            .append("，退房时间 ").append(hotel.getCheckoutTime()).append("\n");
                }
            }
        }
        return sb.toString();
    }

    /** 供 WebSocket 处理器复用的点对点会话队列前缀 */
    public static String chatQueue() {
        return CHAT_QUEUE;
    }
}
