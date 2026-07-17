package com.intelligentresume.resumeimport.controller;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.resumeimport.dto.ResumeImportResponse;
import com.intelligentresume.resumeimport.service.ResumeImportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/resume-imports")
public class ResumeImportController {
    private final ResumeImportService service;

    public ResumeImportController(ResumeImportService service) { this.service = service; }

    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ResumeImportResponse> parse(@RequestPart("file") MultipartFile file,
                                                   HttpServletRequest request) {
        return ApiResponse.success(service.parse(file),
                (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE));
    }
}
