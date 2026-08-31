package com.intelligentresume.communication.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.communication.domain.CommunicationOutputLanguage;
import com.intelligentresume.communication.domain.CommunicationType;
import com.intelligentresume.communication.domain.TemplateScene;
import com.intelligentresume.communication.dto.CommunicationResponse;
import com.intelligentresume.communication.dto.GenerateCommunicationRequest;
import com.intelligentresume.communication.dto.SaveDraftRequest;
import com.intelligentresume.communication.dto.SaveTemplateRequest;
import com.intelligentresume.communication.dto.TemplatePreviewResponse;
import com.intelligentresume.communication.dto.TemplateSummaryResponse;
import com.intelligentresume.communication.dto.UpdateTemplateRequest;
import com.intelligentresume.communication.service.CommunicationService;
import com.intelligentresume.communication.service.CommunicationTemplateService;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

@RestController
@RequestMapping("/api/communications")
public class CommunicationController {
    private final CommunicationService service;
    private final CommunicationTemplateService templateService;
    public CommunicationController(CommunicationService service, CommunicationTemplateService templateService) {
        this.service = service;
        this.templateService = templateService;
    }

    @PostMapping("/generate")
    public ApiResponse<CommunicationResponse> generate(@Valid @RequestBody GenerateCommunicationRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.generate(request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PostMapping("/ai-generate")
    public ResponseEntity<ApiResponse<AiTaskStatusResponse>> generateWithAi(
            @Valid @RequestBody GenerateCommunicationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "缺少 Idempotency-Key");
        }
        AiTaskStatusResponse task = service.generateWithAi(request, idempotencyKey.trim(), currentUserId(httpRequest));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(task, traceId(httpRequest)));
    }

    // ==================== 模板库 ====================

    @GetMapping("/templates")
    public ApiResponse<List<TemplateSummaryResponse>> listTemplates(
            @RequestParam(required = false) TemplateScene scene,
            @RequestParam(required = false) CommunicationType type,
            @RequestParam(required = false) CommunicationOutputLanguage outputLanguage,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(templateService.list(currentUserId(httpRequest), scene, type, outputLanguage),
                traceId(httpRequest));
    }

    @GetMapping("/templates/{id}/preview")
    public ApiResponse<TemplatePreviewResponse> previewTemplate(@PathVariable Long id,
                                                                @RequestParam Long resumeVersionId,
                                                                @RequestParam Long jobDescriptionId,
                                                                HttpServletRequest httpRequest) {
        return ApiResponse.success(templateService.preview(id, resumeVersionId, jobDescriptionId,
                currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PostMapping("/templates")
    public ResponseEntity<ApiResponse<TemplateSummaryResponse>> saveTemplate(
            @Valid @RequestBody SaveTemplateRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(templateService.saveCustom(request, currentUserId(httpRequest)),
                        traceId(httpRequest)));
    }

    @PutMapping("/templates/{id}")
    public ApiResponse<TemplateSummaryResponse> updateTemplate(@PathVariable Long id,
                                                               @Valid @RequestBody UpdateTemplateRequest request,
                                                               HttpServletRequest httpRequest) {
        return ApiResponse.success(templateService.update(id, request, currentUserId(httpRequest)),
                traceId(httpRequest));
    }

    @DeleteMapping("/templates/{id}")
    public ApiResponse<Void> deleteTemplate(@PathVariable Long id, HttpServletRequest httpRequest) {
        templateService.delete(id, currentUserId(httpRequest));
        return ApiResponse.success(null, traceId(httpRequest));
    }

    // ==================== 草稿（确认保存，绝不发送） ====================

    @PostMapping("/drafts")
    public ApiResponse<CommunicationResponse> saveDraft(@Valid @RequestBody SaveDraftRequest request,
                                                        HttpServletRequest httpRequest) {
        return ApiResponse.success(service.saveDraft(request, currentUserId(httpRequest)), traceId(httpRequest));
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
