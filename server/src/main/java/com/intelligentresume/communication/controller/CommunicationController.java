package com.intelligentresume.communication.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.communication.dto.CommunicationGenerateRequest;
import com.intelligentresume.communication.dto.CommunicationGenerateResponse;
import com.intelligentresume.communication.service.CommunicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/communications")
public class CommunicationController {
    private final CommunicationService service;
    public CommunicationController(CommunicationService service) { this.service = service; }
    @PostMapping("/generate")
    public ApiResponse<CommunicationGenerateResponse> generate(@Valid @RequestBody CommunicationGenerateRequest request, HttpServletRequest http) {
        Object userId = http.getAttribute("currentUserId");
        if (userId == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return ApiResponse.success(service.generate(request, (Long) userId),
                (String) http.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE));
    }
}
