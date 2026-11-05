package com.intelligentresume.careermaterial.controller;

import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.careermaterial.dto.*;
import com.intelligentresume.careermaterial.service.CareerMaterialService;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 职业资料 CRUD 控制器。
 *
 * <p>路由与前端 {@code careerMaterial.ts} 契约一致:
 * <ul>
 *     <li>GET /api/career-materials?type=SKILL — 列表(可选类型过滤,参数名 type)</li>
 *     <li>PATCH /api/career-materials/{id} — 更新(前端使用 PATCH)</li>
 * </ul>
 *
 * <p>要点：
 * <ul>
 *     <li>本层只做协议适配（取 userId、包 ApiResponse、补 traceId），所有归属校验与业务
 *         校验都在 Service；不要在 Controller 里补用户判断，否则会与 Service 的
 *         NOT_FOUND 语义分叉；</li>
 *     <li>userId 取自过滤器写入的 request attribute，缺失即 401；不存在"匿名访问"分支；</li>
 *     <li>列表与搜索都不返回原文全文，前端要正文必须再调 GET /{id}；</li>
 *     <li>路由路径、参数名（type / usagePreference / sort）是前端硬依赖，改名属破坏性变更。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/career-materials")
public class CareerMaterialController {

    private final CareerMaterialService service;

    public CareerMaterialController(CareerMaterialService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CareerMaterialDetail>> create(
            @Valid @RequestBody CreateCareerMaterialRequest request, HttpServletRequest httpRequest) {
        CareerMaterialDetail detail = service.create(request, currentUserId(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(detail, traceId(httpRequest)));
    }

    @GetMapping
    public ApiResponse<List<CareerMaterialSummary>> list(
            @RequestParam(required = false) MaterialType type, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.list(currentUserId(httpRequest), type), traceId(httpRequest));
    }

    /** 分页参数的默认值（page=0 / size=25 / sort=updatedAt,desc）是前端不传参时的隐式契约。 */
    @GetMapping("/search")
    public ApiResponse<CareerMaterialSearchPage> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) MaterialType type,
            @RequestParam(required = false) UsagePreference usagePreference,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "updatedAt,desc") String sort,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(service.search(currentUserId(httpRequest), q, type, usagePreference,
                page, size, sort), traceId(httpRequest));
    }

    @GetMapping("/{id}")
    public ApiResponse<CareerMaterialDetail> get(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.get(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PatchMapping("/{id}")
    public ApiResponse<CareerMaterialDetail> update(
            @PathVariable Long id, @Valid @RequestBody UpdateCareerMaterialRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(service.update(id, request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        service.softDelete(id, currentUserId(httpRequest));
        return ApiResponse.success(null, traceId(httpRequest));
    }

    /** 当前登录用户由鉴权链路写入 request attribute；拿不到说明鉴权未执行，按未登录处理。 */
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
