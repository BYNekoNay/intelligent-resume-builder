package com.intelligentresume.ai.material.controller;
import com.intelligentresume.ai.material.dto.MaterialResumeGenerationRequest;
import com.intelligentresume.ai.material.dto.MaterialResumeGenerationResponse;
import com.intelligentresume.ai.material.service.MaterialResumeGenerationService;
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
@RestController @RequestMapping("/api/ai")
public class MaterialResumeGenerationController {
    private final MaterialResumeGenerationService service;
    public MaterialResumeGenerationController(MaterialResumeGenerationService service) { this.service = service; }
    @PostMapping("/generate-resume-from-material")
    public ApiResponse<MaterialResumeGenerationResponse> generate(@Valid @RequestBody MaterialResumeGenerationRequest request, HttpServletRequest http) {
        Object userId = http.getAttribute("currentUserId"); if (userId == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return ApiResponse.success(service.generate(request, (Long) userId), (String) http.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE));
    }
}
