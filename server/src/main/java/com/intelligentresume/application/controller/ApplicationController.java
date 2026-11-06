package com.intelligentresume.application.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.application.dto.*;
import com.intelligentresume.application.service.ApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 投递看板的 HTTP 入口（{@code /api/applications}）。
 *
 * <p><b>① 解决什么业务问题</b>：前端看板页面需要对投递记录做增删改查、拖拽改状态、看转化统计，
 * 这些 HTTP 请求都先落到这里。
 * <p><b>② 在链路哪里</b>：本类只做"接请求 → 取当前用户 → 调 {@code ApplicationService} → 包一层
 * {@code ApiResponse}（带 traceId）"的转发，不写业务规则——规则全在 Service 里。
 * <p><b>③ 改坏它用户会看到什么</b>：漏掉 {@code currentUserId} 校验或新增"按 id 直查"旁路 →
 * 用户能看到/改别人的投递记录（跨用户越权）；返回 201 改成 200 → 前端创建后跳转逻辑可能错乱。
 *
 * <h2>端点分组</h2>
 * <ul>
 *   <li>{@code GET ""} —— 列表，{@code followUp} 只接受 {@code TODAY} / {@code OVERDUE} / {@code ALL}
 *       （其它值静默降级为 {@code ALL}，不报错）；</li>
 *   <li>{@code GET /{id}}、{@code GET /stats} —— 详情与转化统计；</li>
 *   <li>{@code POST ""} —— 创建，返回 <b>201</b>；初始状态只能是 {@code DRAFT}，否则 409；</li>
 *   <li>{@code PUT /{id}} —— 编辑文案/跟进时间。<b>不能改状态</b>：请求里的 status 与当前值不同即 409；</li>
 *   <li>{@code PATCH /{id}/status} —— <b>唯一</b>合法的状态变更入口（白名单 + 乐观锁 + 写历史）；</li>
 *   <li>{@code DELETE /{id}} —— 软删（V40 起），保留期内可恢复。</li>
 * </ul>
 *
 * <h2>两条贯穿所有端点的约定</h2>
 * <ol>
 *   <li><b>归属过滤在服务端做</b>：每个方法都用 {@code currentUserId} 取当前用户，
 *       拿不到即 {@code UNAUTHENTICATED}；不要新增"按 id 直查"的旁路，那是跨用户越权。</li>
 *   <li><b>写操作带 {@code version}</b>：乐观锁冲突返回 409，而不是覆盖别人的编辑。</li>
 * </ol>
 *
 * <p>业务规则与状态机说明见 {@code ApplicationService} 的类注释。
 */
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {
    private final ApplicationService service;

    public ApplicationController(ApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ApplicationSummary>> list(@RequestParam(required = false) String followUp,
                                                      HttpServletRequest httpRequest) {
        return ApiResponse.success(service.list(currentUserId(httpRequest), followUp), traceId(httpRequest));
    }

    @GetMapping("/{id}")
    public ApiResponse<ApplicationResponse> get(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.get(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @GetMapping("/stats")
    public ApiResponse<ApplicationStatsResponse> stats(HttpServletRequest httpRequest) {
        return ApiResponse.success(service.stats(currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ApplicationResponse>> create(@Valid @RequestBody CreateApplicationRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.create(request, currentUserId(httpRequest)), traceId(httpRequest)));
    }

    @PutMapping("/{id}")
    public ApiResponse<ApplicationResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateApplicationRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.update(id, request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<ApplicationResponse> updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateApplicationStatusRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.updateStatus(id, request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        service.delete(id, currentUserId(httpRequest));
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
