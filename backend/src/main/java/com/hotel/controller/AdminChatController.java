package com.hotel.controller;

import com.hotel.common.BusinessException;
import com.hotel.common.PageResult;
import com.hotel.common.Result;
import com.hotel.dto.ChatSessionQueryDTO;
import com.hotel.security.UserContext;
import com.hotel.service.AdminChatService;
import com.hotel.vo.ChatSessionAdminVO;
import com.hotel.vo.ChatSessionDetailVO;
import com.hotel.vo.HotQuestionVO;
import com.hotel.vo.ResolutionStats;
import com.hotel.vo.TrendPoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端客服接口（经营者/管理员）：
 * 会话列表/详情、人工回复、会话标签、数据看板统计
 */
@RestController
@RequestMapping("/api/v1/chat/admin")
@RequiredArgsConstructor
public class AdminChatController {

    private final AdminChatService adminChatService;

    @GetMapping("/sessions")
    public Result<PageResult<ChatSessionAdminVO>> sessions(@Valid ChatSessionQueryDTO dto) {
        requireManager();
        return Result.success(adminChatService.pageSessions(dto));
    }

    @GetMapping("/sessions/{sessionId}")
    public Result<ChatSessionDetailVO> sessionDetail(@PathVariable Long sessionId) {
        requireManager();
        return Result.success(adminChatService.getSessionDetail(sessionId));
    }

    @PostMapping("/sessions/{sessionId}/reply")
    public Result<Void> reply(@PathVariable Long sessionId, @Valid @RequestBody ReplyDTO dto) {
        requireManager();
        adminChatService.reply(sessionId, dto.getContent());
        return Result.success();
    }

    @PutMapping("/sessions/{sessionId}/tag")
    public Result<Void> tag(@PathVariable Long sessionId, @Valid @RequestBody TagDTO dto) {
        requireManager();
        adminChatService.tag(sessionId, dto.getTag());
        return Result.success();
    }

    // ==================== 数据看板 ====================

    @GetMapping("/analytics/trend")
    public Result<List<TrendPoint>> trend(@RequestParam(defaultValue = "7") int days) {
        requireManager();
        return Result.success(adminChatService.trend(days));
    }

    @GetMapping("/analytics/resolution")
    public Result<ResolutionStats> resolution() {
        requireManager();
        return Result.success(adminChatService.resolution());
    }

    @GetMapping("/analytics/hot-questions")
    public Result<List<HotQuestionVO>> hotQuestions(@RequestParam(defaultValue = "10") int limit) {
        requireManager();
        return Result.success(adminChatService.hotQuestions(limit));
    }

    private void requireManager() {
        Integer role = UserContext.getRole();
        if (role == null || role < 1) {
            throw new BusinessException("无权限访问客服工作台");
        }
    }

    @Data
    public static class ReplyDTO {
        @NotBlank(message = "回复内容不能为空")
        private String content;
    }

    @Data
    public static class TagDTO {
        @NotBlank(message = "标签不能为空")
        private String tag;
    }
}
