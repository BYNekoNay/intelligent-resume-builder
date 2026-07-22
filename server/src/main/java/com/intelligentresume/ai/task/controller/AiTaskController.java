package com.intelligentresume.ai.task.controller;

import com.intelligentresume.ai.task.dto.TaskCreateRequest;
import com.intelligentresume.ai.task.dto.TaskResponse;
import com.intelligentresume.ai.task.service.AiTaskService;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/ai")
public class AiTaskController {

    private final AiTaskService service;

    public AiTaskController(AiTaskService service) {
        this.service = service;
    }

    @PostMapping({"/tasks", "/generate-resume-for-job"})
    public ResponseEntity<ApiResponse<TaskResponse>> create(@Valid @RequestBody TaskCreateRequest request,
                                                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                             HttpServletRequest httpRequest) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "Idempotency-Key 不能为空");
        }
        return ResponseEntity.accepted().body(ApiResponse.success(
                service.create(request, idempotencyKey, currentUserId(httpRequest)), traceId(httpRequest)));
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<TaskResponse> get(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.get(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PostMapping("/tasks/{id}/retry")
    public ResponseEntity<ApiResponse<TaskResponse>> retry(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ResponseEntity.accepted().body(ApiResponse.success(
                service.retry(id, currentUserId(httpRequest)), traceId(httpRequest)));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object attr = request.getAttribute("currentUserId");
        if (attr == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return (Long) attr;
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
