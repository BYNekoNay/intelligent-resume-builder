package com.intelligentresume.ai.optimize.controller;

import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.ai.task.dto.CreateAiTaskRequest;
import com.intelligentresume.ai.task.service.AiTaskService;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class InlineOptimizeController {

    private final AiTaskService taskService;

    public InlineOptimizeController(AiTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/inline-optimize")
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> optimize(
            @RequestBody Map<String, Object> body, HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        CreateAiTaskRequest req = new CreateAiTaskRequest(
            AiTaskType.INLINE_OPTIMIZE, body, null, null, null, null, null, null);
        AiTaskStatusResponse resp = taskService.create(req, java.util.UUID.randomUUID().toString(), userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.success(resp, traceId(httpRequest)));
    }

    @PostMapping("/achievement-guidance")
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> guide(
            @RequestBody Map<String, Object> body, HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        CreateAiTaskRequest req = new CreateAiTaskRequest(
            AiTaskType.ACHIEVEMENT_GUIDANCE, body, null, null, null, null, null, null);
        AiTaskStatusResponse resp = taskService.create(req, java.util.UUID.randomUUID().toString(), userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.success(resp, traceId(httpRequest)));
    }

    @PostMapping("/tasks/{id}/retry")
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> retry(
            @PathVariable Long id, HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        AiTaskStatusResponse resp = taskService.retry(id, userId);
        return ResponseEntity.ok(ApiResponse.success(resp, traceId(httpRequest)));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object attr = request.getAttribute("currentUserId");
        if (attr == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return (Long) attr;
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
