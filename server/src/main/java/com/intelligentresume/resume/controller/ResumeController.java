package com.intelligentresume.resume.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.dto.*;
import com.intelligentresume.resume.service.ResumeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 简历主记录的 HTTP 入口（{@code /api/resumes}）。
 *
 * <p><b>① 解决什么业务问题</b>：前端"我的简历"页面需要建简历、改标题、看列表、删简历、
 * 切换当前生效的版本，这些请求都先落到这里。
 * <p><b>② 在链路哪里</b>：只做"接请求 → 取当前用户 → 调 {@code ResumeService} → 包一层
 * {@code ApiResponse}"的转发，不写业务规则——归属校验、指针切换校验全在 Service 里。
 * <p><b>③ 改坏它用户会看到什么</b>：新增"按 id 直查"旁路 → 能看到别人的简历（越权）；
 * 删除端点误调物理删除 → 简历无法恢复。
 *
 * <h2>只管「简历」，不管「版本」</h2>
 * 本类操作的是 {@code resume} 这一层：标题、绑定的 JD、当前版本指针、软删。
 * 版本的保存 / 归档 / 恢复 / 设为当前在 {@link ResumeVersionController}，
 * 两处职责分开，避免「一次编辑同时改两层」的隐式耦合。
 *
 * <h2>端点</h2>
 * <ul>
 *   <li>{@code POST ""} —— 新建，返回 <b>201</b>；</li>
 *   <li>{@code GET ""} / {@code GET /{id}} —— 列表与详情；</li>
 *   <li>{@code GET /by-jd/{jdId}} —— 某 JD 关联的岗位简历，只用于「同 JD 再次生成」时判断新建还是更新，
 *       <b>不是</b>「选一份已有简历作为生成目标」的入口（那个旧入口已被产品移除，不要重新引入）；</li>
 *   <li>{@code PUT /{id}} —— 改标题等元信息；</li>
 *   <li>{@code PATCH /{id}/current-version} —— 切换当前版本指针；目标版本必须属于该简历且未归档；</li>
 *   <li>{@code DELETE /{id}} —— <b>软删</b>，不是物理删除。</li>
 * </ul>
 *
 * <h2>两条贯穿约定</h2>
 * <ol>
 *   <li><b>归属过滤在服务端</b>：每个方法都用 {@code currentUserId}，拿不到即 {@code UNAUTHENTICATED}；
 *       不要新增「按 id 直查」的旁路。</li>
 *   <li><b>当前版本不能归档、归档版本不能设为当前</b> —— 这两条互斥约束由服务层强制，控制器不重复判断。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/resumes")
public class ResumeController {

    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ResumeDetail>> create(
            @Valid @RequestBody CreateResumeRequest request, HttpServletRequest httpRequest) {
        ResumeDetail detail = resumeService.create(request, currentUserId(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(detail, traceId(httpRequest)));
    }

    @GetMapping
    public ApiResponse<List<ResumeSummary>> list(HttpServletRequest httpRequest) {
        return ApiResponse.success(resumeService.list(currentUserId(httpRequest)), traceId(httpRequest));
    }

    /**
     * 查询某 JD 关联的岗位简历列表。用于"同 JD 再次生成"时判断新建/更新。
     */
    @GetMapping("/by-jd/{jdId}")
    public ApiResponse<List<ResumeSummary>> listByJobDescription(
            @PathVariable Long jdId, HttpServletRequest httpRequest) {
        return ApiResponse.success(
                resumeService.listByJobDescription(jdId, currentUserId(httpRequest)),
                traceId(httpRequest));
    }

    @GetMapping("/{id}")
    public ApiResponse<ResumeDetail> get(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(resumeService.get(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PutMapping("/{id}")
    public ApiResponse<ResumeDetail> update(
            @PathVariable Long id, @Valid @RequestBody UpdateResumeRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(
                resumeService.update(id, request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        resumeService.softDelete(id, currentUserId(httpRequest));
        return ApiResponse.success(null, traceId(httpRequest));
    }

    @PatchMapping("/{id}/current-version")
    public ApiResponse<Void> setCurrentVersion(
            @PathVariable Long id, @Valid @RequestBody SetCurrentVersionRequest request,
            HttpServletRequest httpRequest) {
        resumeService.setCurrentVersion(id, request.versionId(), currentUserId(httpRequest));
        return ApiResponse.success(null, traceId(httpRequest));
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
