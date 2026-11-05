package com.intelligentresume.imports.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.imports.dto.ResumeImportResponse;
import com.intelligentresume.imports.service.ResumeImportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历导入控制器：multipart 上传 → 解析 → 返回建议字段。
 *
 * <p>要点：
 * <ul>
 *   <li>唯一入口 {@code POST /api/resume-imports/parse}，{@code consumes} 限定
 *       multipart/form-data，文件名固定为表单字段 {@code file}；</li>
 *   <li>本类<b>不读取 currentUserId</b>：解析结果不落库、不按用户隔离，
 *       解析本身也不依赖用户身份；若将来在此处写库，必须补归属校验；</li>
 *   <li>该路径由 {@code RateLimitRegistry} 的 {@code resume-import-parse} 规则限流，
 *       是全站最重的 CPU 端点之一，不要在这里放宽或绕过限流；</li>
 *   <li>所有格式/大小/超时校验都在 Service，Controller 只负责协议适配与 traceId 回填。</li>
 * </ul>
 */
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
