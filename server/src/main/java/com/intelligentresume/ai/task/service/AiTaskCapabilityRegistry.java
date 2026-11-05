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
 * AI 任务能力的唯一登记处 —— 一个 {@link AiTaskType} 能不能被接受、走哪个执行分支、归哪个领取分组、
 * 需要哪些数据类别授权,全部由这里的描述符决定。
 *
 * <p>要点 / 红线:
 * <ul>
 *   <li>"枚举有值"不等于"能力可用":{@link #requireRegistered} 是通用端点、工作器、provider 三处的
 *       统一入口,未登记一律 VALIDATION。</li>
 *   <li>{@link #descriptors()} 末尾会做完整性校验:新增枚举值但忘了登记,应用启动即失败(fail closed),
 *       这是刻意设计,不要为了"先跑起来"去掉这段。</li>
 *   <li>{@link Group} 决定工作器的线程池与并发额度。分错组会让长任务挤占短任务的执行机会。</li>
 *   <li>{@code genericEndpointAllowed=false} 的类型只能从各自的领域端点创建,不能走 {@code POST /api/ai/tasks}。</li>
 *   <li>{@code baseConsentCategories} 是授权下限;{@code addJobDescriptionWhenPresent=true} 时,
 *       只要快照里出现 JD 字段就追加 {@code JOB_DESCRIPTION} 要求(见 {@link Descriptor})。</li>
 * </ul>
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

    /**
     * 一个任务类型的完整契约。record 不可变,列表字段都已 copyOf,调用方无法改到注册表内部状态。
     */
    public record Descriptor(
            AiTaskType type,
            ExecutionMode executionMode,
            Group group,
            boolean genericEndpointAllowed,
            List<String> baseConsentCategories,
            boolean addJobDescriptionWhenPresent
    ) {
        /**
         * 计算该次输入需要的授权数据类别。
         *
         * <p>只在确实带 JD 时才追加 {@code JOB_DESCRIPTION},避免"没传 JD 也要用户授权 JD 类别"
         * 这种多余拦截。
         */
        public List<String> requiredConsentCategories(Map<String, Object> inputSnapshot) {
            if (!addJobDescriptionWhenPresent || !containsJobDescription(inputSnapshot)) {
                return baseConsentCategories;
            }
            List<String> categories = new ArrayList<>(baseConsentCategories);
            categories.add("JOB_DESCRIPTION");
            return List.copyOf(categories);
        }

        /**
         * 在快照里找 JD 痕迹:先看顶层,再看嵌套的 {@code input} 对象。
         *
         * <p>清单覆盖了四种既有写法({@code jobDescriptionId}/{@code jdText}/{@code targetJdText}/{@code jdContext}),
         * 少一个就可能让某条链路的 JD 数据绕过授权校验 —— 新增 JD 字段时要同步加到这里。
         */
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
