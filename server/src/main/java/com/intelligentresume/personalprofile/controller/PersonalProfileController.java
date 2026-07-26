package com.intelligentresume.personalprofile.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.personalprofile.dto.PersonalProfileRequest;
import com.intelligentresume.personalprofile.dto.PersonalProfileResponse;
import com.intelligentresume.personalprofile.service.PersonalProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/personal-profile")
public class PersonalProfileController {

    private final PersonalProfileService service;

    public PersonalProfileController(PersonalProfileService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PersonalProfileResponse> get(HttpServletRequest httpRequest) {
        return ApiResponse.success(service.get(currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PutMapping
    public ApiResponse<PersonalProfileResponse> upsert(
            @Valid @RequestBody PersonalProfileRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.upsert(request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @GetMapping("/import-suggestion")
    public ApiResponse<PersonalProfileResponse> importSuggestion(
            @RequestParam Long resumeId, HttpServletRequest httpRequest) {
        return ApiResponse.success(
                service.importSuggestion(resumeId, currentUserId(httpRequest)), traceId(httpRequest));
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
