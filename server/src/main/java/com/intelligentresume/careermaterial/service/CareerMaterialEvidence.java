package com.intelligentresume.careermaterial.service;

import com.intelligentresume.careermaterial.domain.CareerMaterial;

import java.util.Collection;
import java.util.Map;

/**
 * Shared evidence-quality boundary for career materials.
 *
 * <p>A title is a label, not evidence. A material is usable when it has
 * non-blank source text or at least one meaningful structured value. The
 * check intentionally stays generic so legacy material types can continue to
 * use their existing JSON shape, while specialized types apply their own
 * required-field validation in {@link CareerMaterialService}.</p>
 */
public final class CareerMaterialEvidence {

    private CareerMaterialEvidence() {
    }

    public static boolean isReady(CareerMaterial material) {
        return material != null && hasMeaningfulEvidence(material.getSourceText(), material.getContentJson());
    }

    /**
     * 读模型重载（ideation #1）：列表查询由 SQL 判定「原文非空」，
     * 结构化证据仍在 Java 判定，与 {@link #isReady(CareerMaterial)} 同一规则。
     */
    public static boolean isReady(boolean hasSourceText, Map<String, Object> contentJson) {
        return hasSourceText || hasMeaningfulValue(contentJson);
    }

    public static boolean hasMeaningfulEvidence(String sourceText, Map<String, Object> contentJson) {
        return hasText(sourceText) || hasMeaningfulValue(contentJson);
    }

    private static boolean hasMeaningfulValue(Object value) {
        if (value == null) return false;
        if (value instanceof String text) return !text.isBlank();
        if (value instanceof Number) return true;
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .filter(entry -> !"title".equalsIgnoreCase(String.valueOf(entry.getKey())))
                    .anyMatch(entry -> hasMeaningfulValue(entry.getValue()));
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().anyMatch(CareerMaterialEvidence::hasMeaningfulValue);
        }
        return true;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
