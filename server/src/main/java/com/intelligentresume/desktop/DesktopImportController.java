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
 * <p>两个端点：{@code /preview} 只做分类（新增 / 跳过 / 冲突），不写任何数据；
 * {@code /apply} 在用户确认后**一次性原子写入**（跨集合外键逐级重映射，中途失败整体回滚）。
 *
 * <p>历史备注：本类注释曾写「写入留到下一批」，那是阶段四上半场的状态；
 * 写入与冲突解决已由提交 {@code 600b1e3} / {@code 33195d7} 落地，
 * 并与真实导出链路一起由 {@code DesktopExportImportRoundTripIT} 守护。
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

    @PostMapping("/apply")
    public ApiResponse<DesktopImportService.ImportResult> apply(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "资源不存在");
        }
        Object pkg = body.get("package");
        if (!(pkg instanceof Map<?, ?>)) {
            throw new BusinessException(ErrorCode.VALIDATION, "请求体缺少 package（工作区包）");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> workspacePackage = (Map<String, Object>) pkg;
        Object rawResolutions = body.get("resolutions");
        @SuppressWarnings("unchecked")
        Map<String, Object> resolutions = rawResolutions instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
        try {
            return ApiResponse.success(importService.apply(workspacePackage, resolutions), traceId(request));
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
