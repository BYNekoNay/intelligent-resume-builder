package com.intelligentresume.ai.guidance.controller;

import com.intelligentresume.ai.guidance.dto.AchievementGuidanceRequest;
import com.intelligentresume.ai.guidance.dto.AchievementGuidanceResponse;
import com.intelligentresume.ai.guidance.service.AchievementGuidanceService;
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
@RequestMapping("/api/ai")
public class AchievementGuidanceController {
    private final AchievementGuidanceService service;
    public AchievementGuidanceController(AchievementGuidanceService service) { this.service = service; }
    @PostMapping("/achievement-guidance")
    public ApiResponse<AchievementGuidanceResponse> guide(@Valid @RequestBody AchievementGuidanceRequest request, HttpServletRequest http) {
        Object userId = http.getAttribute("currentUserId");
        if (userId == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return ApiResponse.success(service.guide(request, (Long) userId),
                (String) http.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE));
    }
}
