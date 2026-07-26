package com.intelligentresume.ai.generation.service;

import com.intelligentresume.careermaterial.domain.CareerMaterial;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds the model-visible representation of a career material. */
@Component
public class CareerMaterialAiSnapshotSanitizer {

    @SuppressWarnings("unchecked")
    public CareerMaterial sanitize(CareerMaterial material) {
        if (!"ACHIEVEMENT".equals(material.getMaterialType().name())) {
            return material;
        }

        Map<String, Object> content = material.getContentJson() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(material.getContentJson());
        String mode = Objects.toString(content.get("metricDisplayMode"), "QUALITATIVE");
        if (!"EXACT".equals(mode)) {
            content.remove("metricExactValue");
        } else if (content.get("metricExactValue") != null) {
            content.put("metricDisplayValue", content.get("metricExactValue"));
        }

        CareerMaterial safe = new CareerMaterial();
        safe.setId(material.getId());
        safe.setUserId(material.getUserId());
        safe.setMaterialType(material.getMaterialType());
        safe.setTitle(material.getTitle());
        safe.setUsagePreference(material.getUsagePreference());
        safe.setContentJson(content);
        safe.setSourceText(renderAchievementSourceText(material.getTitle(), content));
        return safe;
    }

    private String renderAchievementSourceText(String title, Map<String, Object> content) {
        List<String> parts = new ArrayList<>();
        for (Object value : new Object[]{title, content.get("scenario"), content.get("action"),
                content.get("outcome"), content.get("metricName"),
                content.get("metricDisplayValue"), content.get("period")}) {
            String text = nonBlank(value);
            if (!text.isBlank()) parts.add(text);
        }
        return String.join("; ", parts);
    }

    private String nonBlank(Object value) {
        String text = value == null ? "" : value.toString().trim();
        return text;
    }
}
