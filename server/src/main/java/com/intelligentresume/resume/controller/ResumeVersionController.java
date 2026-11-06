package com.intelligentresume.resume.controller;

import com.intelligentresume.auth.jwt.JwtAuthenticationFilter;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.dto.ResumeVersionDetail;
import com.intelligentresume.resume.dto.ResumeVersionSummary;
import com.intelligentresume.resume.dto.RestoreResumeVersionRequest;
import com.intelligentresume.resume.dto.SaveVersionRequest;
import com.intelligentresume.resume.service.JsonResumeExporter;
import com.intelligentresume.resume.service.ResumeVersionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 简历版本相关 HTTP 入口：保存、列表、详情、恢复、归档/取消归档，以及 JSON Resume 导出。
 *
 * <p><b>① 解决什么业务问题</b>：前端版本历史面板需要保存新版本、翻历史、回滚（恢复）、
 * 归档不想要的版本，以及把简历导出成 JSON Resume 标准格式带走。
 * <p><b>② 在链路哪里</b>：请求先到这里，再转 {@link ResumeVersionService}（业务规则）与
 * {@link JsonResumeExporter}（导出映射）；与 {@code ResumeController} 分工——那边管主档容器，这边管版本内容。
 * <p><b>③ 改坏它用户会看到什么</b>：保存/恢复误返回 200 而非 201 → 前端无法区分"新增了版本"；
 * 导出改成统一信封 → 客户端下载文件要多解一层包；归属校验绕过 → 能读别人的版本内容。
 *
 * <p>要点：
 * <ul>
 *     <li>控制器只做参数取用与响应包装，业务规则（归属、状态、版本号）全在 {@link ResumeVersionService}。</li>
 *     <li>用户身份从 {@code JwtAuthenticationFilter.CURRENT_USER_ID_ATTRIBUTE} 对应的请求属性取（由鉴权过滤器写入），
 *         取不到直接 401，不做匿名兜底。</li>
 *     <li>保存与恢复都返回 201：两者都会**新增**一行版本，不是幂等更新。</li>
 *     <li>归档/取消归档返回空体的统一信封，失败由全局异常处理映射状态码。</li>
 * </ul>
 */
@RestController
public class ResumeVersionController {

    private final ResumeVersionService versionService;
    private final JsonResumeExporter jsonResumeExporter;

    public ResumeVersionController(ResumeVersionService versionService, JsonResumeExporter jsonResumeExporter) {
        this.versionService = versionService;
        this.jsonResumeExporter = jsonResumeExporter;
    }

    @PostMapping("/api/resumes/{resumeId}/versions")
    public ResponseEntity<ApiResponse<ResumeVersionDetail>> save(
            @PathVariable Long resumeId, @Valid @RequestBody SaveVersionRequest request,
            HttpServletRequest httpRequest) {
        ResumeVersionDetail detail = versionService.save(resumeId, request, currentUserId(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(detail, traceId(httpRequest)));
    }

    @GetMapping("/api/resumes/{resumeId}/versions")
    public ApiResponse<List<ResumeVersionSummary>> list(
            @PathVariable Long resumeId,
            @RequestParam(defaultValue = "false") boolean archived,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(
                versionService.listByResume(resumeId, archived, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PostMapping("/api/resumes/{resumeId}/versions/{versionId}/restore")
    public ResponseEntity<ApiResponse<ResumeVersionDetail>> restore(
            @PathVariable Long resumeId, @PathVariable Long versionId,
            @Valid @RequestBody(required = false) RestoreResumeVersionRequest request,
            HttpServletRequest httpRequest) {
        ResumeVersionDetail detail = versionService.restore(resumeId, versionId, request, currentUserId(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(detail, traceId(httpRequest)));
    }

    @PostMapping("/api/resumes/{resumeId}/versions/{versionId}/archive")
    public ApiResponse<Void> archive(
            @PathVariable Long resumeId, @PathVariable Long versionId, HttpServletRequest httpRequest) {
        versionService.archive(resumeId, versionId, currentUserId(httpRequest));
        return ApiResponse.success(null, traceId(httpRequest));
    }

    @PostMapping("/api/resumes/{resumeId}/versions/{versionId}/unarchive")
    public ApiResponse<Void> unarchive(
            @PathVariable Long resumeId, @PathVariable Long versionId, HttpServletRequest httpRequest) {
        versionService.unarchive(resumeId, versionId, currentUserId(httpRequest));
        return ApiResponse.success(null, traceId(httpRequest));
    }

    @GetMapping("/api/resume-versions/{id}")
    public ApiResponse<ResumeVersionDetail> get(
            @PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(versionService.get(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    /**
     * 导出 JSON Resume v1.0.0 文档（B4）。
     *
     * <p>返回**附件下载**而不是统一 {@code ApiResponse} 信封：它是文件，客户端要按
     * {@code Content-Disposition} 落盘，套一层 envelope 反而要多一次解包。
     * 与账号导出（{@code POST /api/auth/export}）同一形态。
     *
     * <p>归属校验复用 {@code versionService.get}，因此他人版本 404、无权限不泄露存在性。
     */
    @GetMapping("/api/resume-versions/{id}/json-resume")
    public ResponseEntity<byte[]> exportJsonResume(@PathVariable Long id, HttpServletRequest httpRequest) {
        ResumeVersionDetail detail = versionService.get(id, currentUserId(httpRequest));
        Map<String, Object> document = jsonResumeExporter.export(
                detail.resumeJson(), detail.resumeId(), detail.versionNo());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + jsonResumeExporter.fileName(detail.resumeId(), detail.versionNo()) + "\"")
                .body(jsonResumeExporter.toJsonBytes(document));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object attr = request.getAttribute(JwtAuthenticationFilter.CURRENT_USER_ID_ATTRIBUTE);
        if (attr == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        return (Long) attr;
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
