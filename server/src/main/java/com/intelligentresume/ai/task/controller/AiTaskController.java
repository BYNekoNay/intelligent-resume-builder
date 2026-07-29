package com.intelligentresume.ai.task.controller;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/ai")
public class AiTaskController {

    private final AiTaskService taskService;

    public AiTaskController(AiTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/tasks")
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> createTask(
            @Valid @RequestBody CreateAiTaskRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest servletRequest) {
        if (request.taskType() == AiTaskType.JOB_GENERATION
                || request.taskType() == AiTaskType.JOB_MATERIAL_SELECTION
                || request.taskType() == AiTaskType.COMMUNICATION_GENERATE) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "This AI task must start from its domain endpoint");
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? UUID.randomUUID().toString() : idempotencyKey;
        AiTaskStatusResponse task = taskService.create(request, key, currentUserId(servletRequest));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(task,
                        (String) servletRequest.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE)));
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<AiTaskStatusResponse> getTask(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.success(taskService.get(id, currentUserId(request)),
                (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object id = request.getAttribute("currentUserId");
        if (id == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return (Long) id;
    }
}
