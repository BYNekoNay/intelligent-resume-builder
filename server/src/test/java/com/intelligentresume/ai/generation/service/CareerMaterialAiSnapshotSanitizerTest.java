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
