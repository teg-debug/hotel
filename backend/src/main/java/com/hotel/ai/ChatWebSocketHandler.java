package com.hotel.ai;

import com.hotel.ai.push.UserPushPublisher;
import com.hotel.common.BusinessException;
import com.hotel.entity.User;
import com.hotel.mapper.UserMapper;
import com.hotel.security.UserContext;
import com.hotel.service.ChatService;
import com.hotel.vo.ChatMessageVO;
import com.hotel.vo.ChatReplyVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 智能客服 WebSocket 处理器（STOMP）：
 * <ul>
 *   <li>客户端发送：STOMP 帧到 /app/chat.send，载荷 {"sessionId":1,"content":"..."}</li>
 *   <li>回复推送：发送到该用户自己的 /user/queue/chat，而不是可被任意订阅的广播主题</li>
 *   <li>连接后发送 /app/chat.join（载荷 {"sessionId":1}）可拉取历史记录</li>
 * </ul>
 *
 * <p>用户身份来自握手阶段 JWT 解析出的 Principal；处理期间会把用户标识与角色写入
 * UserContext，使 Function Calling 工具取到的身份与 REST 路径完全一致
 * （此前只写入了用户标识，任何依赖角色的服务在该路径下都会判定为无权限）。</p>
 */
@Slf4j
@Controller
public class ChatWebSocketHandler {

    /** 与 ChatServiceImpl 中的点对点队列保持一致 */
    private static final String CHAT_QUEUE = "/queue/chat";
    /** 单条消息长度上限，与 REST 路径一致 */
    private static final int MAX_CONTENT_LENGTH = 500;

    private final ChatService chatService;
    private final UserPushPublisher userPushPublisher;
    private final UserMapper userMapper;

    public ChatWebSocketHandler(ChatService chatService,
                               UserPushPublisher userPushPublisher,
                               UserMapper userMapper) {
        this.chatService = chatService;
        this.userPushPublisher = userPushPublisher;
        this.userMapper = userMapper;
    }

    /** 发送消息：/app/chat.send */
    @MessageMapping("/chat.send")
    public void sendMessage(WsChatRequest request, Principal principal) {
        String user = principal.getName();
        try {
            bindUserContext(user);
            if (request == null || request.sessionId() == null) {
                throw new BusinessException("缺少会话编号");
            }
            if (request.content() == null || request.content().isBlank()) {
                throw new BusinessException("消息内容不能为空");
            }
            if (request.content().length() > MAX_CONTENT_LENGTH) {
                throw new BusinessException("单条消息不能超过 " + MAX_CONTENT_LENGTH + " 个字");
            }
            ChatReplyVO reply = chatService.sendMessage(request.sessionId(), request.content());
            sendToUser(user, reply);
        } catch (Exception e) {
            log.error("WS 消息处理失败 user={}", user, e);
            sendToUser(user, new ChatReplyVO(
                    request == null ? null : request.sessionId(),
                    e instanceof BusinessException ? e.getMessage() : "系统繁忙，请稍后再试或转人工客服。",
                    null, 0.0, false, null, "rule"));
        } finally {
            UserContext.clear();
        }
    }

    /** 加入会话：/app/chat.join → 推送历史记录 */
    @MessageMapping("/chat.join")
    public void join(WsChatRequest request, Principal principal) {
        String user = principal.getName();
        try {
            bindUserContext(user);
            if (request == null || request.sessionId() == null) {
                throw new BusinessException("缺少会话编号");
            }
            List<ChatMessageVO> history = chatService.history(request.sessionId());
            sendToUser(user, Map.of("type", "history", "sessionId", request.sessionId(), "data", history));
        } catch (Exception e) {
            log.error("WS 拉取历史失败 user={}", user, e);
            sendToUser(user, Map.of("type", "error",
                    "message", e instanceof BusinessException ? e.getMessage() : "拉取历史记录失败"));
        } finally {
            UserContext.clear();
        }
    }

    /** 写入当前用户上下文：工具与业务服务依赖这里的身份与角色 */
    private void bindUserContext(String userId) {
        Long id = Long.valueOf(userId);
        User account = userMapper.selectById(id);
        if (account == null || (account.getStatus() != null && account.getStatus() == 0)) {
            throw new BusinessException("账号不存在或已被禁用");
        }
        UserContext.set(id, account.getUsername(), account.getRole());
    }

    /**
     * 只投递到该用户自己的点对点队列。
     *
     * <p>经 {@link UserPushPublisher} 走 Redis 跨实例扇出：用户重连后可能已经落在
     * 另一个实例上，直接调本机模板会找不到连接而丢弃消息。</p>
     */
    private void sendToUser(String userId, Object payload) {
        userPushPublisher.push(userId, CHAT_QUEUE, payload);
    }

    /** WS 载荷 */
    public record WsChatRequest(Long sessionId, String content) {
    }
}
