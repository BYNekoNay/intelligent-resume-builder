package com.intelligentresume.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;

/** Rejects known development credentials before a production instance accepts traffic. */
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

    public ProductionConfigurationValidator(
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.pdf.service-token}") String pdfServiceToken,
            @Value("${spring.datasource.password}") String datasourcePassword,
            @Value("${app.ai.bailian.api-key}") String bailianApiKey,
            @Value("${app.jwt.refresh-cookie.secure}") boolean secureCookie) {
        this.jwtSecret = jwtSecret;
        this.pdfServiceToken = pdfServiceToken;
        this.datasourcePassword = datasourcePassword;
        this.bailianApiKey = bailianApiKey;
        this.secureCookie = secureCookie;
    }

    @PostConstruct
    void validate() {
        requireStrong("JWT_SECRET", jwtSecret, 32);
        requireStrong("PDF_SERVICE_TOKEN", pdfServiceToken, 32);
        requireStrong("SPRING_DATASOURCE_PASSWORD", datasourcePassword, 16);
        requireConfigured("BAILIAN_API_KEY", bailianApiKey);
        if (!secureCookie) {
            throw new IllegalStateException("COOKIE_SECURE must be true when the prod profile is active");
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
