package com.intelligentresume.ai.task.service;

import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.common.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    @DisplayName("秒级任务必须留在 LIGHT 分组：否则短任务仍会被分钟级长任务饿死")
    void secondScaleTasksStayInTheLightGroup() {
        // 这三类正是 OPEN-DECISIONS「内联润色平时 13s 被饿死 8 分钟」实测涉及的秒级任务；
        // 分组领取的价值全在于它们不被 HEAVY 长任务阻塞——若被改回 HEAVY，
        // 现有 DatabaseTaskWorkerTest 仍会全绿（桩按 typesIn(group) 匹配），饿死会静默复发。
        assertEquals(AiTaskCapabilityRegistry.Group.LIGHT,
                AiTaskCapabilityRegistry.groupOf(AiTaskType.INLINE_OPTIMIZE));
        assertEquals(AiTaskCapabilityRegistry.Group.LIGHT,
                AiTaskCapabilityRegistry.groupOf(AiTaskType.RESUME_OPTIMIZE));
        assertEquals(AiTaskCapabilityRegistry.Group.LIGHT,
                AiTaskCapabilityRegistry.groupOf(AiTaskType.INTERVIEW_COACH));
        assertFalse(AiTaskCapabilityRegistry.typesIn(AiTaskCapabilityRegistry.Group.LIGHT).isEmpty(),
                "LIGHT 分组不能为空");
    }
}
