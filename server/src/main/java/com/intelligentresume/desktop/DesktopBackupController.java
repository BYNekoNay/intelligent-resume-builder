package com.intelligentresume.desktop;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.config.DesktopModeProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 桌面本地工作区的备份与恢复端点（ADR-012 阶段三 / R30 / R39）。
 *
 * <p>只在桌面模式可用：在线部署里这些端点不存在（404），避免误把「本地工作区快照」
 * 当成云端能力。
 */
@RestController
@RequestMapping("/api/desktop/backups")
public class DesktopBackupController {

    private final DesktopBackupService backupService;
    private final DesktopModeProperties properties;

    public DesktopBackupController(DesktopBackupService backupService,
                                  DesktopModeProperties properties) {
        this.backupService = backupService;
        this.properties = properties;
    }

    @GetMapping
    public ApiResponse<List<DesktopBackupService.BackupInfo>> list(HttpServletRequest request) {
        requireDesktopMode(request);
        return ApiResponse.success(backupService.listBackups(), traceId(request));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DesktopBackupService.BackupInfo>> create(
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        requireDesktopMode(request);
        String label = body == null ? null : body.get("label");
        DesktopBackupService.BackupInfo info = backupService.createBackup(label, false);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(info, traceId(request)));
    }

    @PostMapping("/{backupId}/restore")
    public ResponseEntity<ApiResponse<DesktopBackupService.RestoreResult>> restore(
            @PathVariable String backupId,
            HttpServletRequest request) {
        requireDesktopMode(request);
        DesktopBackupService.RestoreResult result = backupService.restoreBackup(backupId);
        // 失败时用 409 表达「没有完成恢复」，且响应体里带上原因与是否已回滚
        HttpStatus status = result.ok() ? HttpStatus.OK : HttpStatus.CONFLICT;
        return ResponseEntity.status(status)
                .body(ApiResponse.success(result, traceId(request)));
    }

    private void requireDesktopMode(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            // 非桌面模式：这些端点当作不存在，避免把「本地工作区快照」误当云端能力
            throw new BusinessException(ErrorCode.NOT_FOUND, "资源不存在");
        }
    }

    private String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(
                com.intelligentresume.common.api.TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return traceId == null ? null : traceId.toString();
    }
}
