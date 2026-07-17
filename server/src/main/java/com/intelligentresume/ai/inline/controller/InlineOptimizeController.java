package com.intelligentresume.ai.inline.controller;

import com.intelligentresume.ai.inline.dto.InlineOptimizeRequest;
import com.intelligentresume.ai.inline.dto.InlineOptimizeResponse;
import com.intelligentresume.ai.inline.service.InlineOptimizeService;
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
public class InlineOptimizeController {

    private final InlineOptimizeService service;

    public InlineOptimizeController(InlineOptimizeService service) {
        this.service = service;
    }

    @PostMapping("/inline-optimize")
    public ApiResponse<InlineOptimizeResponse> optimize(@Valid @RequestBody InlineOptimizeRequest request,
                                                        HttpServletRequest httpRequest) {
        Object userId = httpRequest.getAttribute("currentUserId");
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return ApiResponse.success(service.optimize(request, (Long) userId),
                (String) httpRequest.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE));
    }
}
