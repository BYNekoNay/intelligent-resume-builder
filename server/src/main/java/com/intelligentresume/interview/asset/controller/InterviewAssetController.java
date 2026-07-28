package com.intelligentresume.interview.asset.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.interview.asset.dto.InterviewAssetRequest;
import com.intelligentresume.interview.asset.dto.InterviewAssetResponse;
import com.intelligentresume.interview.asset.service.InterviewAssetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/interview-answer-assets")
public class InterviewAssetController {
    private final InterviewAssetService service;

    public InterviewAssetController(InterviewAssetService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<InterviewAssetResponse>> list(@RequestParam(required = false) Long jobDescriptionId,
                                                          @RequestParam(required = false) String keyword,
                                                          HttpServletRequest httpRequest) {
        return ApiResponse.success(service.list(currentUserId(httpRequest), jobDescriptionId, keyword), traceId(httpRequest));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<InterviewAssetResponse>> create(@Valid @RequestBody InterviewAssetRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.create(request, currentUserId(httpRequest)), traceId(httpRequest)));
    }

    @PutMapping("/{id}")
    public ApiResponse<InterviewAssetResponse> update(@PathVariable Long id, @Valid @RequestBody InterviewAssetRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.update(id, request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        service.delete(id, currentUserId(httpRequest));
        return ApiResponse.success(null, traceId(httpRequest));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute("currentUserId");
        return value instanceof Long id ? id : null;
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
