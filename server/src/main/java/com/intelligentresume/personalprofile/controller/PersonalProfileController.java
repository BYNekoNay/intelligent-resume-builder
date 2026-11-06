package com.intelligentresume.personalprofile.controller;

import com.intelligentresume.auth.jwt.JwtAuthenticationFilter;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.personalprofile.dto.PersonalProfileRequest;
import com.intelligentresume.personalprofile.dto.PersonalProfileResponse;
import com.intelligentresume.personalprofile.service.PersonalProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人档案的三个端点：读取、整份保存、从简历导入候选值。
 *
 * <p>链路位置：前端档案页 → 本类 → {@code PersonalProfileService}；
 * 保存动作产出档案行，供生成简历时服务端合并联系方式。
 *
 * <p>一人一份（{@code user_id} 唯一），三个端点都把「当前登录用户」作为唯一的数据范围；
 * {@code import-suggestion} 只读不写，返回的候选值必须经用户确认，详见
 * {@link PersonalProfileService} 头部的隐私边界说明。</p>
 */
@RestController
@RequestMapping("/api/personal-profile")
public class PersonalProfileController {

    private final PersonalProfileService service;

    public PersonalProfileController(PersonalProfileService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PersonalProfileResponse> get(HttpServletRequest httpRequest) {
        return ApiResponse.success(service.get(currentUserId(httpRequest)), traceId(httpRequest));
    }

    @PutMapping
    public ApiResponse<PersonalProfileResponse> upsert(
            @Valid @RequestBody PersonalProfileRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(service.upsert(request, currentUserId(httpRequest)), traceId(httpRequest));
    }

    @GetMapping("/import-suggestion")
    public ApiResponse<PersonalProfileResponse> importSuggestion(
            @RequestParam Long resumeId, HttpServletRequest httpRequest) {
        return ApiResponse.success(
                service.importSuggestion(resumeId, currentUserId(httpRequest)), traceId(httpRequest));
    }

    /** 与其他控制器相同的取用户 id 约定：过滤器没放进请求属性就按未登录处理（40301）。 */
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
