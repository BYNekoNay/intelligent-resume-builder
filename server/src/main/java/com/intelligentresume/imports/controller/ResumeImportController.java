package com.intelligentresume.imports.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.imports.dto.ResumeImportResponse;
import com.intelligentresume.imports.service.ResumeImportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/resume-imports")
public class ResumeImportController {
    private final ResumeImportService service;
    public ResumeImportController(ResumeImportService service) { this.service = service; }

    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ResumeImportResponse> parse(@RequestPart("file") MultipartFile file, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.parse(file), traceId(httpRequest));
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
