package com.intelligentresume.ai.task.service;

import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Single registration point for the server-side AI task contract.
 *
 * <p>A task enum value is not a capability by itself. Every value must have a
 * descriptor here before it can be accepted by the generic task endpoint or
 * reach a worker/provider. The explicit completeness check deliberately fails
 * closed when a new enum value is added without its capability entry.</p>
 */
public final class AiTaskCapabilityRegistry {

    public enum ExecutionMode {
        DOMAIN_SELECTION,
        DOMAIN_GENERATION,
        ATS_ANALYSIS,
        COMMUNICATION,
        INTERVIEW,
        PROVIDER
    }

    public record Descriptor(
            AiTaskType type,
            ExecutionMode executionMode,
            boolean genericEndpointAllowed,
            List<String> baseConsentCategories,
            boolean addJobDescriptionWhenPresent
    ) {
        public List<String> requiredConsentCategories(Map<String, Object> inputSnapshot) {
            if (!addJobDescriptionWhenPresent || !containsJobDescription(inputSnapshot)) {
                return baseConsentCategories;
            }
            List<String> categories = new ArrayList<>(baseConsentCategories);
            categories.add("JOB_DESCRIPTION");
            return List.copyOf(categories);
        }

        private static boolean containsJobDescription(Map<String, Object> inputSnapshot) {
            return present(field(inputSnapshot, "jobDescriptionId"))
                    || present(field(inputSnapshot, "jdText"))
                    || present(field(inputSnapshot, "targetJdText"))
                    || present(field(inputSnapshot, "jdContext"));
        }

        private static Object field(Map<String, Object> inputSnapshot, String name) {
            if (inputSnapshot == null) return null;
            Object value = inputSnapshot.get(name);
            if (value != null) return value;
            Object nestedInput = inputSnapshot.get("input");
            if (nestedInput instanceof Map<?, ?> input) return input.get(name);
            return null;
        }

        private static boolean present(Object value) {
            return value instanceof CharSequence text ? !text.toString().isBlank() : value != null;
        }
    }

    private static final Map<AiTaskType, Descriptor> DESCRIPTORS = descriptors();

    private AiTaskCapabilityRegistry() {
    }

    public static Descriptor requireRegistered(AiTaskType type) {
        if (type == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "AI task type is required");
        }
        Descriptor descriptor = DESCRIPTORS.get(type);
        if (descriptor == null) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "AI task type is not registered: " + type.name());
        }
        return descriptor;
    }

    public static Map<AiTaskType, Descriptor> descriptorsForTests() {
        return Map.copyOf(DESCRIPTORS);
    }

    private static Map<AiTaskType, Descriptor> descriptors() {
        EnumMap<AiTaskType, Descriptor> descriptors = new EnumMap<>(AiTaskType.class);
        put(descriptors, AiTaskType.JOB_MATERIAL_SELECTION, ExecutionMode.DOMAIN_SELECTION,
                false, List.of("JOB_DESCRIPTION", "CAREER_MATERIAL", "PERSONAL_PROFILE"), false);
        put(descriptors, AiTaskType.JOB_GENERATION, ExecutionMode.DOMAIN_GENERATION,
                false, List.of("JOB_DESCRIPTION", "CAREER_MATERIAL", "PERSONAL_PROFILE"), false);
        put(descriptors, AiTaskType.RESUME_OPTIMIZE, ExecutionMode.PROVIDER,
                true, List.of("RESUME"), true);
        put(descriptors, AiTaskType.INLINE_OPTIMIZE, ExecutionMode.PROVIDER,
                true, List.of("RESUME"), true);
        put(descriptors, AiTaskType.MATERIAL_IMPORT, ExecutionMode.PROVIDER,
                true, List.of("CAREER_MATERIAL"), false);
        put(descriptors, AiTaskType.ACHIEVEMENT_GUIDANCE, ExecutionMode.PROVIDER,
                true, List.of("RESUME"), true);
        put(descriptors, AiTaskType.COMMUNICATION_GENERATE, ExecutionMode.COMMUNICATION,
                false, List.of("RESUME", "JOB_DESCRIPTION"), false);
        put(descriptors, AiTaskType.INTERVIEW_COACH, ExecutionMode.INTERVIEW,
                true, List.of("RESUME", "INTERVIEW_ANSWER"), true);
        put(descriptors, AiTaskType.ATS_ANALYSIS, ExecutionMode.ATS_ANALYSIS,
                true, List.of("RESUME", "JOB_DESCRIPTION"), false);

        EnumSet<AiTaskType> missing = EnumSet.allOf(AiTaskType.class);
        missing.removeAll(descriptors.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Unregistered AI task capabilities: " + missing);
        }
        return Map.copyOf(descriptors);
    }

    private static void put(Map<AiTaskType, Descriptor> descriptors,
                            AiTaskType type,
                            ExecutionMode executionMode,
                            boolean genericEndpointAllowed,
                            List<String> baseConsentCategories,
                            boolean addJobDescriptionWhenPresent) {
        if (descriptors.put(type, new Descriptor(type, executionMode, genericEndpointAllowed,
                List.copyOf(baseConsentCategories), addJobDescriptionWhenPresent)) != null) {
            throw new IllegalStateException("Duplicate AI task capability: " + type);
        }
    }
}
