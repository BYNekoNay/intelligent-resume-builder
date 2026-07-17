package com.intelligentresume.ats.controller;

import com.intelligentresume.ats.dto.AtsCheckRequest;
import com.intelligentresume.ats.dto.AtsCheckResponse;
import com.intelligentresume.ats.service.AtsCheckService;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ats")
public class AtsCheckController {
    private final AtsCheckService service;
    public AtsCheckController(AtsCheckService service) { this.service = service; }
    @PostMapping("/check")
    public ApiResponse<AtsCheckResponse> check(@Valid @RequestBody AtsCheckRequest request, HttpServletRequest http) {
        Object userId = http.getAttribute("currentUserId");
        if (userId == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return ApiResponse.success(service.check(request, (Long) userId),
                (String) http.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE));
    }
}
