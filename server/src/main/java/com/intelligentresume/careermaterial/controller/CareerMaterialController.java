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
