package com.intelligentresume.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * 生产环境启动前的配置体检：命中开发默认值/弱配置就**拒绝启动**（而不是等到请求时才暴露）。
 *
 * <p>只在 {@code prod} profile 生效，{@code @PostConstruct} 阶段抛异常，因此错误会在部署时立刻可见。
 *
 * <p>边界：
 * <ul>
 *   <li>只做「长度 + 已知占位符子串」检查，**不评估熵**；真正的密钥管理在部署侧（Vault/环境变量）。</li>
 *   <li>{@link #UNSAFE_VALUES} 是 contains 匹配：新增示例/默认密钥时要同步加进列表，否则会被当作合法值放过。</li>
 *   <li>强制 {@code COOKIE_SECURE=true}：prod 走 HTTPS，secure=false 会让 refresh Cookie 明文传输。</li>
 *   <li>强制信任转发头：prod 流量经两层代理，不信任会让所有客户端共用一个代理 IP 的限流桶。</li>
 * </ul>
 */
@Component
@Profile("prod")
public class ProductionConfigurationValidator {

    private static final List<String> UNSAFE_VALUES = List.of(
            "dev-only-change-me-please-32bytes-min-length-required",
            "dev-pdf-token-change-me",
            "resume_app_dev",
            "root_dev_only",
            "change-me",
            "replace-with"
    );

    private final String jwtSecret;
    private final String pdfServiceToken;
    private final String datasourcePassword;
    private final String bailianApiKey;
    private final boolean secureCookie;
    private final boolean trustForwardedHeaders;

    public ProductionConfigurationValidator(
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.pdf.service-token}") String pdfServiceToken,
            @Value("${spring.datasource.password}") String datasourcePassword,
            @Value("${app.ai.bailian.api-key}") String bailianApiKey,
            @Value("${app.jwt.refresh-cookie.secure}") boolean secureCookie,
            @Value("${app.security.rate-limit.trust-forwarded-headers:false}") boolean trustForwardedHeaders) {
        this.jwtSecret = jwtSecret;
        this.pdfServiceToken = pdfServiceToken;
        this.datasourcePassword = datasourcePassword;
        this.bailianApiKey = bailianApiKey;
        this.secureCookie = secureCookie;
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    /** 生产 profile 下的启动校验入口：任一项不合规都抛 IllegalStateException，容器不进入可用状态。 */
    @PostConstruct
    void validate() {
        requireStrong("JWT_SECRET", jwtSecret, 32);
        requireStrong("PDF_SERVICE_TOKEN", pdfServiceToken, 32);
        requireStrong("SPRING_DATASOURCE_PASSWORD", datasourcePassword, 16);
        requireConfigured("BAILIAN_API_KEY", bailianApiKey);
        if (!secureCookie) {
            throw new IllegalStateException("COOKIE_SECURE must be true when the prod profile is active");
        }
        if (!trustForwardedHeaders) {
            // 生产流量经 edge → web 两层代理到达 API：不信任转发头会把所有客户端收敛到
            // 代理地址这一个限流分桶（登录 10/min 等阈值退化为全站共享）。仓库内最外层
            // 代理已用 $remote_addr 覆写 X-Forwarded-For，消除伪造风险，故此处强制开启。
            throw new IllegalStateException("RATE_LIMIT_TRUST_FORWARDED_HEADERS must be true when the prod profile is active: "
                    + "without trusting the forwarded header every client shares one rate-limit bucket (the proxy address)");
        }
    }

    private void requireStrong(String name, String value, int minimumLength) {
        if (value == null || value.isBlank() || value.length() < minimumLength || isUnsafe(value)) {
            throw new IllegalStateException(name + " must be a non-default secret with at least "
                    + minimumLength + " characters when the prod profile is active");
        }
    }

    private void requireConfigured(String name, String value) {
        if (value == null || value.isBlank() || isUnsafe(value)) {
            throw new IllegalStateException(name + " must be configured with a non-placeholder value when the prod profile is active");
        }
    }

    private boolean isUnsafe(String value) {
        String normalized = value.trim().toLowerCase();
        return UNSAFE_VALUES.stream().anyMatch(candidate -> normalized.contains(candidate));
    }
}
