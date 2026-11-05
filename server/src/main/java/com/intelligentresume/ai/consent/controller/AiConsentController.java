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
 *
 * <p>要点:
 * <ul>
 *   <li>{@code GET /api/ai/consent} 返回最新事件;用户从未同意过时返回 body 为 null 的成功响应,
 *       不是 404 —— 前端据此展示"未同意"。</li>
 *   <li>授权与撤回都是"追加事件",重复调用是安全的(撤回除外:没有历史记录会报 NOT_FOUND)。</li>
 *   <li>userId 只从认证过滤器写入的请求属性取,不接受请求参数。</li>
 * </ul>
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
