package com.intelligentresume.ai.optimize.controller;

import com.intelligentresume.ai.optimize.dto.InlineOptimizeRequest;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.ai.task.dto.CreateAiTaskRequest;
import com.intelligentresume.ai.task.service.AiTaskService;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class InlineOptimizeController {

    private final AiTaskService taskService;
    private final ResumeVersionRepository resumeVersionRepository;
    private final JobDescriptionRepository jobDescriptionRepository;

    public InlineOptimizeController(AiTaskService taskService, ResumeVersionRepository resumeVersionRepository,
                                    JobDescriptionRepository jobDescriptionRepository) {
        this.taskService = taskService;
        this.resumeVersionRepository = resumeVersionRepository;
        this.jobDescriptionRepository = jobDescriptionRepository;
    }

    @PostMapping("/inline-optimize")
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> optimize(
            @Valid @RequestBody InlineOptimizeRequest request, HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        validateOwnedResources(request, userId);
        Map<String, Object> input = toInputMap(request);
        CreateAiTaskRequest req = new CreateAiTaskRequest(
            AiTaskType.INLINE_OPTIMIZE, input, null, null, null, null, null, null);
        AiTaskStatusResponse resp = taskService.create(req, java.util.UUID.randomUUID().toString(), userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.success(resp, traceId(httpRequest)));
    }

    @PostMapping("/achievement-guidance")
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> guide(
            @Valid @RequestBody InlineOptimizeRequest request, HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        validateOwnedResources(request, userId);
        Map<String, Object> input = toInputMap(request);
        CreateAiTaskRequest req = new CreateAiTaskRequest(
            AiTaskType.ACHIEVEMENT_GUIDANCE, input, null, null, null, null, null, null);
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

    private Map<String, Object> toInputMap(InlineOptimizeRequest request) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("resumeVersionId", request.getResumeVersionId());
        map.put("section", request.getSection());
        map.put("content", request.getContent());
        if (request.getJdContext() != null) {
            map.put("jdContext", request.getJdContext());
        }
        if (request.getJobDescriptionId() != null) {
            map.put("jobDescriptionId", request.getJobDescriptionId());
        }
        return map;
    }

    private void validateOwnedResources(InlineOptimizeRequest request, Long userId) {
        resumeVersionRepository.findByIdAndCreatedByAndDeletedAtIsNull(request.getResumeVersionId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Resume version not found"));
        if (request.getJobDescriptionId() != null) {
            jobDescriptionRepository.findByIdAndUserId(request.getJobDescriptionId(), userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Job description not found"));
        }
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
