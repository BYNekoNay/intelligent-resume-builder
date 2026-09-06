package com.intelligentresume.ai.task.service;

import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiTaskCapabilityRegistryTest {

    @Test
    void everyPersistableTaskTypeHasOneExplicitCapability() {
        assertEquals(AiTaskType.values().length,
                AiTaskCapabilityRegistry.descriptorsForTests().size());
        for (AiTaskType type : AiTaskType.values()) {
            assertEquals(type, AiTaskCapabilityRegistry.requireRegistered(type).type());
        }
    }

    @Test
    void optionalJobDescriptionIsAddedOnlyWhenPresentInSnapshot() {
        assertEquals(
                java.util.List.of("RESUME"),
                AiTaskCapabilityRegistry.requireRegistered(AiTaskType.INLINE_OPTIMIZE)
                        .requiredConsentCategories(Map.of("content", "text")));
        assertEquals(
                java.util.List.of("RESUME", "JOB_DESCRIPTION"),
                AiTaskCapabilityRegistry.requireRegistered(AiTaskType.INLINE_OPTIMIZE)
                        .requiredConsentCategories(Map.of("input", Map.of("jobDescriptionId", 9L))));
    }

    @Test
    void nullTypeFailsClosedBeforePersistenceOrProviderWork() {
        assertThrows(BusinessException.class,
                () -> AiTaskCapabilityRegistry.requireRegistered(null));
    }
}
