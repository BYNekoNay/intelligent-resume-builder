package com.intelligentresume.ai.consent.controller;

import com.intelligentresume.ai.consent.dto.ConsentResponse;
import com.intelligentresume.ai.consent.dto.GrantConsentRequest;
import com.intelligentresume.ai.consent.service.AiConsentService;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * AI 同意管理控制器。
 */
@RestController
@RequestMapping("/api/ai/consent")
public class AiConsentController {

    private final AiConsentService consentService;

    public AiConsentController(AiConsentService consentService) {
        this.consentService = consentService;
    }

    /**
     * 获取当前同意状态。
     */
    @GetMapping
    public ApiResponse<ConsentResponse> current(HttpServletRequest httpRequest) {
        ConsentResponse response = consentService.current(currentUserId(httpRequest));
        return ApiResponse.success(response, traceId(httpRequest));
    }

    /**
     * 授权 AI 数据处理。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ConsentResponse>> grant(
            @Valid @RequestBody GrantConsentRequest request, HttpServletRequest httpRequest) {
        ConsentResponse response = consentService.grant(request, currentUserId(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, traceId(httpRequest)));
    }

    /**
     * 撤回 AI 数据处理同意。
     */
    @DeleteMapping
    public ApiResponse<ConsentResponse> withdraw(HttpServletRequest httpRequest) {
        ConsentResponse response = consentService.withdraw(currentUserId(httpRequest));
        return ApiResponse.success(response, traceId(httpRequest));
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
