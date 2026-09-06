package com.intelligentresume.ai.generation.service;

import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CareerMaterialAiSnapshotSanitizerTest {

    private final CareerMaterialAiSnapshotSanitizer sanitizer = new CareerMaterialAiSnapshotSanitizer();

    @Test
    void qualitativeAchievementDoesNotExposeExactMetric() {
        CareerMaterial material = achievement("QUALITATIVE");

        CareerMaterial safe = sanitizer.sanitize(material);

        assertFalse(safe.getContentJson().containsKey("metricExactValue"));
        assertFalse(safe.getSourceText().contains("128000"));
    }

    @Test
    void rangeAchievementDoesNotExposeExactMetric() {
        CareerMaterial material = achievement("RANGE");

        CareerMaterial safe = sanitizer.sanitize(material);

        assertEquals("20%+", safe.getContentJson().get("metricDisplayValue"));
        assertFalse(safe.getContentJson().containsKey("metricExactValue"));
    }

    @Test
    void exactAchievementUsesExactMetricAsDisplayValue() {
        CareerMaterial material = achievement("EXACT");

        CareerMaterial safe = sanitizer.sanitize(material);

        assertEquals("128000", safe.getContentJson().get("metricDisplayValue"));
        assertEquals("128000", safe.getContentJson().get("metricExactValue"));
    }

    @Test
    void ordinaryMaterialRedactsPiiFromSourceAndNestedContent() {
        CareerMaterial material = new CareerMaterial();
        material.setMaterialType(MaterialType.WORK_EXPERIENCE);
        material.setTitle("Backend contact me at jane@example.com");
        material.setSourceText("Address: 1 Example Road; phone 13800138000; https://example.com");
        material.setContentJson(Map.of(
                "company", "Example",
                "email", "jane@example.com",
                "details", Map.of("phone", "13800138000", "summary", "Contact jane@example.com"),
                "highlights", java.util.List.of("Built services; see https://example.com/docs")));

        CareerMaterial safe = sanitizer.sanitize(material);

        assertEquals("Backend contact me at [EMAIL]", safe.getTitle());
        assertEquals("[ADDRESS]; phone [PHONE]; [URL]", safe.getSourceText());
        assertFalse(safe.getContentJson().containsKey("email"));
        assertFalse(((Map<?, ?>) safe.getContentJson().get("details")).containsKey("phone"));
        assertEquals("Contact [EMAIL]", ((Map<?, ?>) safe.getContentJson().get("details")).get("summary"));
        assertEquals("Built services; see [URL]", ((java.util.List<?>) safe.getContentJson().get("highlights")).get(0));
    }

    private CareerMaterial achievement(String displayMode) {
        CareerMaterial material = new CareerMaterial();
        material.setMaterialType(MaterialType.valueOf("ACHIEVEMENT"));
        material.setTitle("Scale checkout conversion");
        material.setSourceText("Revenue 128000 must remain private");
        material.setContentJson(Map.of(
                "scenario", "Checkout optimization",
                "action", "Refactored the payment flow",
                "outcome", "Improved conversion",
                "period", "2025",
                "metricName", "Conversion rate",
                "metricDisplayMode", displayMode,
                "metricDisplayValue", "20%+",
                "metricExactValue", "128000"));
        return material;
    }
}
