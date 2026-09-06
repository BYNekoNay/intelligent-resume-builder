package com.intelligentresume.ai.task.service;

import com.intelligentresume.ai.task.domain.AiTaskType;

import java.util.List;
import java.util.Map;

/**
 * Central data-category policy for AI task input.
 *
 * <p>The same snapshot-derived categories must be used before a task is
 * persisted and again immediately before provider execution.  Keeping the
 * optional JD detection here also covers domain endpoints that put their JD
 * reference inside the nested {@code input} object.
 */
public final class AiTaskConsentPolicy {

    private AiTaskConsentPolicy() {
    }

    public static List<String> requiredCategories(AiTaskType type, Map<String, Object> inputSnapshot) {
        return AiTaskCapabilityRegistry.requireRegistered(type)
                .requiredConsentCategories(inputSnapshot);
    }
}
