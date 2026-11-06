package com.intelligentresume.jobdescription.controller;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.dto.*;
import com.intelligentresume.jobdescription.service.JobDescriptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * JD 管理控制器：岗位页的 HTTP 入口（增删改查 + 触发解析）。
 *
 * <p>链路位置：前端 {@code jobDescription.ts} → 本类 → {@code JobDescriptionService}。
 * 改坏它用户会看到什么：parse 返回类型改小（不回完整详情）会破坏前端"解析后直接刷新页面"
 * 的契约；漏 currentUserId 校验会变成匿名可访问。
 *
 * <p>路由与前端 {@code jobDescription.ts} 契约一致:
 * PATCH 更新;POST /{id}/parse 返回完整 JobDescriptionDetail。
 *
 * <p>要点：
 * <ul>
 *     <li>每个入口都要求登录态（从 request attribute 取 userId），缺失即 401；</li>
 *     <li>列表不分页、不接收过滤参数，按 updatedAt 倒序返回该用户全部 JD，
 *         前端要搜索只能自行过滤——改动前先确认是否已引入大数据量用户；</li>
 *     <li>{@code /{id}/reference} 专供"只需要标题/公司"的关联展示，不要用 GET /{id} 替代；</li>
 *     <li>parse 是"重算并覆盖"：解析确定性保证同文同结果，但每次都会刷新 parsedAt；
 *         返回解析后的完整详情，前端可直接刷新页面，无需再发 GET。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/jobs")
public class JobDescriptionController {

    private final JobDescriptionService service;

    public JobDescriptionController(JobDescriptionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<JobDescriptionDetail>> create(
            @Valid @RequestBody CreateJobDescriptionRequest request, HttpServletRequest httpRequest) {
        JobDescriptionDetail detail = service.create(request, currentUserId(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(detail, traceId(httpRequest)));
    }

    @GetMapping
    public ApiResponse<List<JobDescriptionSummary>> list(HttpServletRequest httpRequest) {
        return ApiResponse.success(service.list(currentUserId(httpRequest)), traceId(httpRequest));
    }

    @GetMapping("/{id}")
    public ApiResponse<JobDescriptionDetail> get(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.get(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @GetMapping("/{id}/reference")
    public ApiResponse<JobDescriptionReference> reference(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.reference(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PatchMapping("/{id}")
    public ApiResponse<JobDescriptionDetail> update(
            @PathVariable Long id, @Valid @RequestBody UpdateJobDescriptionRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(service.update(id, request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        service.softDelete(id, currentUserId(httpRequest));
        return ApiResponse.success(null, traceId(httpRequest));
    }

    @PostMapping("/{id}/parse")
    public ApiResponse<JobDescriptionDetail> parse(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.parse(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    /** 当前登录用户由鉴权链路写入 request attribute；缺失即 401，不存在匿名访问分支。 */
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
