package com.hotel.controller;

import com.hotel.common.BusinessException;
import com.hotel.common.PageResult;
import com.hotel.common.Result;
import com.hotel.dto.ChatMessageDTO;
import com.hotel.dto.ChatStartDTO;
import com.hotel.dto.KnowledgeImportDTO;
import com.hotel.dto.KnowledgeUpdateDTO;
import com.hotel.dto.RateDTO;
import com.hotel.dto.TicketStatusDTO;
import com.hotel.dto.TransferDTO;
import com.hotel.security.UserContext;
import com.hotel.service.ChatService;
import com.hotel.service.KnowledgeService;
import com.hotel.service.TicketService;
import com.hotel.vo.ChatMessageVO;
import com.hotel.vo.ChatReplyVO;
import com.hotel.vo.ChatSessionVO;
import com.hotel.vo.KnowledgeVO;
import com.hotel.vo.ServiceTicketVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 智能客服 REST 接口
 * <ul>
 *   <li>会话：start / message / message/stream(SSE) / history / transfer / rate</li>
 *   <li>知识库：import(FAQ) / upload(文档) / update / delete / page（经营者/管理员）</li>
 *   <li>工单：列表 / 状态处理（前台/经营者/管理员）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    /** 流式推送超时：长回答需要较长时间，超时过短会在生成中途断开 */
    private static final long STREAM_TIMEOUT_MS = 180_000L;

    private final ChatService chatService;
    private final KnowledgeService knowledgeService;
    private final TicketService ticketService;

    /**
     * 流式推送线程池。
     *
     * <p>按类型注入即可：容器里以 {@code ExecutorService} 注册的 Bean 只有这一个
     * （Spring Boot 自带的任务执行器实现的是 {@code AsyncTaskExecutor}，不是 ExecutorService）。
     * 这里不用 {@code @Qualifier} —— 项目未启用 Lombok 的注解拷贝，
     * 字段上的 @Qualifier 不会传到 {@code @RequiredArgsConstructor} 生成的构造参数上。</p>
     */
    private final ExecutorService streamExecutor;

    // ==================== 会话 ====================

    @PostMapping("/session/start")
    public Result<ChatSessionVO> start(@Valid @RequestBody ChatStartDTO dto) {
        return Result.success(chatService.startSession(dto));
    }

    @GetMapping("/sessions")
    public Result<PageResult<ChatSessionVO>> mySessions(@RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "10") int size) {
        return Result.success(chatService.pageMySessions(page, size));
    }

    @PostMapping("/session/{sessionId}/message")
    public Result<ChatReplyVO> sendMessage(@PathVariable Long sessionId,
                                           @Valid @RequestBody ChatMessageDTO dto) {
        return Result.success(chatService.sendMessage(sessionId, dto.getContent()));
    }

    /**
     * 流式发送消息（SSE）：逐字推送模型输出。
     *
     * <p>事件类型：{@code chunk} 文本分片、{@code done} 完整结果、{@code error} 错误消息。</p>
     *
     * <p>两处必须在异步线程上显式处理的事情：一是还原当前用户身份，
     * 因为 ThreadLocal 不跨线程继承，而下游服务普遍依赖它做数据隔离；
     * 二是校验放在提交响应前同步执行，否则业务错误只能以事件形式返回、HTTP 状态码仍是 200。</p>
     */
    @PostMapping(value = "/session/{sessionId}/message/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(@PathVariable Long sessionId,
                                    @Valid @RequestBody ChatMessageDTO dto) {
        chatService.assertSendable(sessionId, dto.getContent());

        Long userId = UserContext.getUserId();
        String username = UserContext.getUsername();
        Integer role = UserContext.getRole();

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        streamExecutor.execute(() -> {
            try {
                UserContext.set(userId, username, role);
                ChatReplyVO reply = chatService.streamMessage(sessionId, dto.getContent(),
                        chunk -> emit(emitter, "chunk", Map.of("content", chunk)));
                emit(emitter, "done", reply);
            } catch (BusinessException e) {
                emit(emitter, "error", Map.of("msg", e.getMessage()));
            } catch (Exception e) {
                log.error("流式推送失败 sessionId={}", sessionId, e);
                emit(emitter, "error", Map.of("msg", "服务异常，请稍后重试"));
            } finally {
                UserContext.clear();
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 连接已断开，无需再处理
                }
            }
        });
        return emitter;
    }

    /**
     * 推送一个 SSE 事件。
     *
     * <p>发送失败通常是客户端已断开（例如用户切走页面），此时只记日志：
     * 回答仍会落库，用户回来拉历史记录时能看到完整内容。</p>
     */
    private void emit(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("SSE 事件推送失败，连接可能已关闭 event={}", event, e);
        }
    }

    @GetMapping("/session/{sessionId}/history")
    public Result<java.util.List<ChatMessageVO>> history(@PathVariable Long sessionId) {
        return Result.success(chatService.history(sessionId));
    }

    @PostMapping("/session/{sessionId}/transfer")
    public Result<ChatSessionVO> transfer(@PathVariable Long sessionId,
                                          @RequestBody(required = false) TransferDTO dto) {
        return Result.success(chatService.transfer(sessionId, dto == null ? null : dto.getReason()));
    }

    @PostMapping("/session/{sessionId}/rate")
    public Result<ChatSessionVO> rate(@PathVariable Long sessionId, @Valid @RequestBody RateDTO dto) {
        return Result.success(chatService.rate(sessionId, dto));
    }

    // ==================== 知识库（经营者/管理员） ====================

    @PostMapping("/knowledge/import")
    public Result<Integer> importFaq(@Valid @RequestBody KnowledgeImportDTO dto) {
        requireManager();
        return Result.success(knowledgeService.importFaq(dto));
    }

    @PostMapping(value = "/knowledge/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Integer> upload(@RequestParam("file") MultipartFile file,
                                  @RequestParam(required = false) Long hotelId,
                                  @RequestParam(required = false) String category) {
        requireManager();
        return Result.success(knowledgeService.uploadDocument(file, hotelId, category));
    }

    @DeleteMapping("/knowledge/{id}")
    public Result<Void> deleteKnowledge(@PathVariable Long id) {
        requireManager();
        knowledgeService.delete(id);
        return Result.success();
    }

    /** 编辑知识条目：内容变更后重建向量；归属酒店不可变更 */
    @PutMapping("/knowledge/{id}")
    public Result<KnowledgeVO> updateKnowledge(@PathVariable Long id,
                                              @Valid @RequestBody KnowledgeUpdateDTO dto) {
        requireManager();
        return Result.success(knowledgeService.update(id, dto));
    }

    @GetMapping("/knowledge/page")
    public Result<PageResult<KnowledgeVO>> knowledgePage(@RequestParam(required = false) Long hotelId,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "10") int size) {
        requireManager();
        return Result.success(knowledgeService.page(hotelId, page, size));
    }

    // ==================== 服务工单（前台/经营者/管理员） ====================

    @GetMapping("/tickets")
    public Result<PageResult<ServiceTicketVO>> tickets(@RequestParam(required = false) Long hotelId,
                                                       @RequestParam(required = false) Integer status,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "10") int size) {
        return Result.success(ticketService.pageTickets(hotelId, status, page, size));
    }

    @PutMapping("/tickets/{id}/status")
    public Result<ServiceTicketVO> updateTicketStatus(@PathVariable Long id,
                                                      @Valid @RequestBody TicketStatusDTO dto) {
        return Result.success(ticketService.updateStatus(id, dto));
    }

    // ==================== 权限校验 ====================

    /** 知识库管理需经营者/管理员（role >= 1） */
    private void requireManager() {
        Integer role = UserContext.getRole();
        if (role == null || role < 1) {
            throw new BusinessException("无权限操作知识库");
        }
    }
}
