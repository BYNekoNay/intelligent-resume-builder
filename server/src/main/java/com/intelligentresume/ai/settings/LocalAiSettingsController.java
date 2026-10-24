package com.intelligentresume.ai.settings;

import com.intelligentresume.auth.jwt.JwtAuthenticationFilter;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.config.DesktopModeProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 桌面端本地 AI 设置端点（ADR-012 阶段五 / R31）。
 *
 * <p>与其它 `/api/desktop/**` 一致：**只在 desktop profile 下存在**，在线部署返回 404
 * （已登录 404 / 匿名 401，见 `SecurityConfig`）。前端据此把入口藏起来。
 *
 * <p>密钥处理：写接口可以带 {@code apiKey}（存进 DPAPI 密文文件），但**任何读接口都不回传它** ——
 * 页面只需要知道"有没有存过"。
 */
@RestController
@RequestMapping("/api/desktop/ai-settings")
public class LocalAiSettingsController {

    private final LocalAiSettingService service;
    private final DesktopModeProperties properties;

    public LocalAiSettingsController(LocalAiSettingService service, DesktopModeProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    /** 读取设置；未配置时返回默认值（不落库），让页面有可填的内容。 */
    @GetMapping
    public ApiResponse<LocalAiSettingService.View> get(HttpServletRequest request) {
        requireDesktopMode();
        return ApiResponse.success(service.getOrDefault(currentUserId(request)), traceId(request));
    }

    /** 保存设置。{@code apiKey} 为空表示"只改其它字段"，不会清掉已有密钥。 */
    @PutMapping
    public ApiResponse<LocalAiSettingService.View> save(HttpServletRequest request,
                                                        @RequestBody LocalAiSettingService.UpdateRequest body) {
        requireDesktopMode();
        if (body == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "请求体不能为空");
        }
        return ApiResponse.success(service.save(currentUserId(request), body), traceId(request));
    }

    /** 删除设置与加密凭据（R31 的"删"）。 */
    @DeleteMapping
    public ApiResponse<Void> delete(HttpServletRequest request) {
        requireDesktopMode();
        service.delete(currentUserId(request));
        return ApiResponse.success(null, traceId(request));
    }

    /**
     * 显式「测试连接」（R31）：会真的向所配端点发一次最小请求。
     * 请求体可空；带 {@code apiKey} 时用它测（允许"先测再存"）。
     */
    @PostMapping("/test")
    public ApiResponse<LocalAiSettingService.TestResult> test(HttpServletRequest request,
                                                              @RequestBody(required = false) TestRequest body) {
        requireDesktopMode();
        String apiKey = body == null ? null : body.apiKey();
        return ApiResponse.success(service.test(currentUserId(request), apiKey), traceId(request));
    }

    /** 只承载"可能要测的未保存 key"；其余字段用已保存的设置。 */
    public record TestRequest(String apiKey) {
    }

    private void requireDesktopMode() {
        if (!properties.isEnabled()) {
            // 与其它桌面端点同一口径：在线部署一律 404，不暴露"这里有个桌面功能"
            throw new BusinessException(ErrorCode.NOT_FOUND, "资源不存在");
        }
    }

    private Long currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute(JwtAuthenticationFilter.CURRENT_USER_ID_ATTRIBUTE);
        if (value instanceof Long id) {
            return id;
        }
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }

    private String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(com.intelligentresume.common.api.TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return traceId == null ? null : traceId.toString();
    }
}
