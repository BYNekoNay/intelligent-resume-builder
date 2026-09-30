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

    /**
     * 工作器领取分组。分钟级长任务与秒级短任务分属不同分组，各自持有独立
     * 执行线程池与并发额度，避免长任务把短任务饿死。
     */
    public enum Group {
        /** 分钟级长任务：单条会长时间占用额度。 */
        HEAVY,
        /** 秒级短任务：必须保证不被长任务阻塞。 */
        LIGHT
    }

    public record Descriptor(
            AiTaskType type,
            ExecutionMode executionMode,
            Group group,
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

    /** 任务类型所属的领取分组。 */
    public static Group groupOf(AiTaskType type) {
        return requireRegistered(type).group();
    }

    /**
     * 某个分组下的全部任务类型，按枚举声明顺序返回，供工作器构造领取过滤条件。
     */
    public static List<AiTaskType> typesIn(Group group) {
        return DESCRIPTORS.values().stream()
                .filter(descriptor -> descriptor.group() == group)
                .map(Descriptor::type)
                .toList();
    }

    public static Map<AiTaskType, Descriptor> descriptorsForTests() {
        return Map.copyOf(DESCRIPTORS);
    }

    private static Map<AiTaskType, Descriptor> descriptors() {
        EnumMap<AiTaskType, Descriptor> descriptors = new EnumMap<>(AiTaskType.class);
        put(descriptors, AiTaskType.JOB_MATERIAL_SELECTION, ExecutionMode.DOMAIN_SELECTION, Group.HEAVY,
                false, List.of("JOB_DESCRIPTION", "CAREER_MATERIAL", "PERSONAL_PROFILE"), false);
        put(descriptors, AiTaskType.JOB_GENERATION, ExecutionMode.DOMAIN_GENERATION, Group.HEAVY,
                false, List.of("JOB_DESCRIPTION", "CAREER_MATERIAL", "PERSONAL_PROFILE"), false);
        put(descriptors, AiTaskType.RESUME_OPTIMIZE, ExecutionMode.PROVIDER, Group.LIGHT,
                true, List.of("RESUME"), true);
        put(descriptors, AiTaskType.INLINE_OPTIMIZE, ExecutionMode.PROVIDER, Group.LIGHT,
                true, List.of("RESUME"), true);
        put(descriptors, AiTaskType.MATERIAL_IMPORT, ExecutionMode.PROVIDER, Group.LIGHT,
                true, List.of("CAREER_MATERIAL"), false);
        put(descriptors, AiTaskType.ACHIEVEMENT_GUIDANCE, ExecutionMode.PROVIDER, Group.LIGHT,
                true, List.of("RESUME"), true);
        put(descriptors, AiTaskType.COMMUNICATION_GENERATE, ExecutionMode.COMMUNICATION, Group.HEAVY,
                false, List.of("RESUME", "JOB_DESCRIPTION"), false);
        put(descriptors, AiTaskType.INTERVIEW_COACH, ExecutionMode.INTERVIEW, Group.LIGHT,
                true, List.of("RESUME", "INTERVIEW_ANSWER"), true);
        put(descriptors, AiTaskType.ATS_ANALYSIS, ExecutionMode.ATS_ANALYSIS, Group.HEAVY,
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
                            Group group,
                            boolean genericEndpointAllowed,
                            List<String> baseConsentCategories,
                            boolean addJobDescriptionWhenPresent) {
        if (descriptors.put(type, new Descriptor(type, executionMode, group, genericEndpointAllowed,
                List.copyOf(baseConsentCategories), addJobDescriptionWhenPresent)) != null) {
            throw new IllegalStateException("Duplicate AI task capability: " + type);
        }
    }
}
