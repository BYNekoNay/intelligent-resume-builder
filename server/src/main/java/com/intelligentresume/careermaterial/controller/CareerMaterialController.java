package com.intelligentresume.careermaterial.controller;

import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.dto.CareerMaterialCreateRequest;
import com.intelligentresume.careermaterial.dto.CareerMaterialResponse;
import com.intelligentresume.careermaterial.service.CareerMaterialService;
import com.intelligentresume.resume.dto.ResumeMaterialReferenceResponse;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/career-materials")
public class CareerMaterialController {

    private final CareerMaterialService service;

    public CareerMaterialController(CareerMaterialService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<CareerMaterialResponse> create(@Valid @RequestBody CareerMaterialCreateRequest request,
                                                     HttpServletRequest httpRequest) {
        return ApiResponse.success(service.create(request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @GetMapping
    public ApiResponse<List<CareerMaterialResponse>> list(@RequestParam(required = false) MaterialType type,
                                                          HttpServletRequest httpRequest) {
        return ApiResponse.success(service.list(currentUserId(httpRequest), type), traceId(httpRequest));
    }

    @GetMapping("/{id}")
    public ApiResponse<CareerMaterialResponse> get(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.get(id, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PatchMapping("/{id}")
    public ApiResponse<CareerMaterialResponse> update(@PathVariable Long id,
                                                     @Valid @RequestBody CareerMaterialCreateRequest request,
                                                     HttpServletRequest httpRequest) {
        return updateMaterial(id, request, httpRequest);
    }

    @PutMapping("/{id}")
    public ApiResponse<CareerMaterialResponse> replace(@PathVariable Long id,
                                                       @Valid @RequestBody CareerMaterialCreateRequest request,
                                                       HttpServletRequest httpRequest) {
        return updateMaterial(id, request, httpRequest);
    }

    private ApiResponse<CareerMaterialResponse> updateMaterial(Long id,
                                                                CareerMaterialCreateRequest request,
                                                                HttpServletRequest httpRequest) {
        return ApiResponse.success(service.update(id, request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        service.softDelete(id, currentUserId(httpRequest));
        return ApiResponse.success(null, traceId(httpRequest));
    }

    @GetMapping("/{id}/references")
    public ApiResponse<List<ResumeMaterialReferenceResponse>> references(@PathVariable Long id,
                                                                          HttpServletRequest request) {
        return ApiResponse.success(service.references(id, currentUserId(request)), traceId(request));
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
