package com.intelligentresume.desktop;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.config.DesktopModeProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 桌面端的 Web → 桌面迁移入口（ADR-012 阶段四 / R27–R29 / R37–R38 / F5）。
 *
 * <p>本版只提供**预览与分类**：导入会先把包拆成「新增 / 跳过 / 冲突」三类交给你确认，
 * 确认之后的写入（涉及跨集合外键重映射）留到下一批 —— 与其先做一个会整批回滚的半成品，
 * 不如先把判断逻辑做扎实。
 */
@RestController
@RequestMapping("/api/desktop/import")
public class DesktopImportController {

    private final DesktopImportService importService;
    private final DesktopModeProperties properties;

    public DesktopImportController(DesktopImportService importService,
                                   DesktopModeProperties properties) {
        this.importService = importService;
        this.properties = properties;
    }

    @PostMapping("/preview")
    public ApiResponse<DesktopImportService.ImportPreview> preview(
            @RequestBody Map<String, Object> workspacePackage,
            HttpServletRequest request) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "资源不存在");
        }
        try {
            return ApiResponse.success(importService.preview(workspacePackage), traceId(request));
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, ex.getMessage());
        }
    }

    private String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(
                com.intelligentresume.common.api.TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return traceId == null ? null : traceId.toString();
    }
}
