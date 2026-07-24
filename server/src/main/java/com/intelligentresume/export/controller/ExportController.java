package com.intelligentresume.export.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.export.dto.CreateExportRequest;
import com.intelligentresume.export.dto.ExportTaskStatusResponse;
import com.intelligentresume.export.service.ExportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * PDF 导出控制器。
 *
 * <p>路由:
 * <ul>
 *   <li>POST /api/exports/pdf — 创建导出任务(202)</li>
 *   <li>GET /api/exports/tasks/{id} — 查询状态</li>
 *   <li>GET /api/exports/files/{id} — 下载 PDF</li>
 *   <li>POST /api/exports/tasks/{id}/retry — 重试失败任务</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/exports")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    /**
     * 创建 PDF 导出任务。返回 202 Accepted。
     */
    @PostMapping("/pdf")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ExportTaskStatusResponse> create(
            @Valid @RequestBody CreateExportRequest request,
            HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        ExportTaskStatusResponse response = exportService.create(request, userId);
        return ApiResponse.success(response, traceId(httpRequest));
    }

    /**
     * 查询导出任务状态。
     */
    @GetMapping("/tasks/{id}")
    public ApiResponse<ExportTaskStatusResponse> getTask(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        return ApiResponse.success(exportService.get(id, userId), traceId(httpRequest));
    }

    /**
     * 重试失败的导出任务。
     */
    @PostMapping("/tasks/{id}/retry")
    public ApiResponse<ExportTaskStatusResponse> retry(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        return ApiResponse.success(exportService.retry(id, userId), traceId(httpRequest));
    }

    /**
     * 下载 PDF 文件。Content-Type: application/pdf。
     * 不暴露 storageKey,通过 taskId 间接访问。
     */
    @GetMapping("/files/{id}")
    public ResponseEntity<Resource> download(
            @PathVariable Long id,
            HttpServletRequest httpRequest) {
        Long userId = currentUserId(httpRequest);
        Resource resource = exportService.download(id, userId);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"resume.pdf\"")
                .body(resource);
    }

    private Long currentUserId(HttpServletRequest request) {
        Object attr = request.getAttribute("currentUserId");
        if (attr instanceof Long id) {
            return id;
        }
        throw new BusinessException(ErrorCode.UNAUTHENTICATED, "未登录");
    }

    private String traceId(HttpServletRequest request) {
        Object attr = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return attr != null ? attr.toString() : null;
    }
}
