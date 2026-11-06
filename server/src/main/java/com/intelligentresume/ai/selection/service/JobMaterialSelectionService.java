package com.intelligentresume.ai.selection.service;

import com.intelligentresume.ai.provider.AiCallContext;
import com.intelligentresume.ai.provider.AiCallResult;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.service.CareerMaterialEvidence;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 岗位选材：把候选资料交给模型挑出与 JD 相关的部分，再把结果收敛成可信结构。
 *
 * <p>新人三问：①业务问题：用户不知道哪些资料适合投这个岗位，这里让 AI 当"参谋"
 * 推荐最多 12 条并说明理由；②链路位置：后台 worker 执行 JOB_MATERIAL_SELECTION 任务时调用；
 * 上游 {@code MaterialSelectionContextBuilder} 筛候选，下游产出 recommended/unselected 写进
 * 任务结果，供选材页展示与确认（{@code MaterialSelectionConfirmationService}）；③改坏它用户会看到：
 * 推荐里出现用户没确认过的资料（越权）、分数/理由乱来、或推荐条数失控。
 *
 * <h2>这段代码保证的四件事（review 时逐条对）</h2>
 * <ol>
 *   <li><b>模型不能越界</b>：返回的 {@code materialId} 必须落在候选集合里，幻觉 ID 或过期 ID
 *       直接丢弃（见 {@link #parseRecommendations}）—— 不能让模型把用户没确认的资料拉进生成链路；</li>
 *   <li><b>条数有硬上限</b>：模型最多 12 条；用户强制勾选的先入列，再补 AI 推荐，总数封顶 30。
 *       上游 {@code MaterialSelectionContextBuilder} 已保证 {@code forced ≤ 30}；</li>
 *   <li><b>分数不可信也要夹紧</b>：{@code relevanceScore} 一律夹到 0~100，模型给 999 也不放行；</li>
 *   <li><b>没选上的也要有说法</b>：每条未采用资料都带 reason，排除原因按
 *       「证据不足 &gt; 本次手动排除 &gt; 全局偏好」取值 —— 顺序反了会误导用户去改设置。</li>
 * </ol>
 *
 * <h2>改这里之前</h2>
 * 候选筛选与 prompt 组装在 {@code MaterialSelectionContextBuilder}，**预览端点共用同一个类**，
 * 所以「预览看到的」与「这里实际发送的」必然同源（R32）。不要在本类另拼一份提示词，
 * 那会让"发送前预览"直接变成谎言。
 *
 * <p>本类不读个人档案：联系方式只在生成**之后**由 {@code JobGenerationService#mergePersonalProfile}
 * 服务端合并，不经过模型。历史上这里注入过 {@code PersonalProfileRepository} 却从未使用，已删除 ——
 * 保留只会让人误以为选材读到了姓名/邮箱/电话。
 */
@Service
public class JobMaterialSelectionService {

    private static final int MAX_RECOMMENDED = 12;

    private final JobDescriptionRepository jobRepository;
    private final MaterialSelectionContextBuilder contextBuilder;
    private final AiProviderRegistry providerRegistry;

    public JobMaterialSelectionService(JobDescriptionRepository jobRepository,
                                       MaterialSelectionContextBuilder contextBuilder,
                                       AiProviderRegistry providerRegistry) {
        this.jobRepository = jobRepository;
        this.contextBuilder = contextBuilder;
        this.providerRegistry = providerRegistry;
    }

    /**
     * 选材任务的主流程，①→⑥：
     * ①从快照取岗位并校验归属（任务对应的 JD 必须还在且属于本人）；
     * ②筛候选 + 拼 prompt（与预览共用 {@code MaterialSelectionContextBuilder}，见类注释）；
     * ③候选为空直接返回"没有可用素材"——不调模型，白烧配额还产出假结论；
     * ④调模型拿推荐；
     * ⑤收敛推荐：先让模型输出过 {@link #parseRecommendations} 信任边界，再把用户强制勾选的
     *    以 score=100 优先入列、AI 推荐补足到 30 封顶；
     * ⑥落选的候选也给出原因（unselected），连同排除名单一起写结果。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> executeTask(AiTask task) {
        Map<String, Object> snapshot = task.getInputSnapshotJson();
        Long jobId = longValue(snapshot.get("jobDescriptionId"));
        JobDescription job = jobRepository.findByIdAndUserId(jobId, task.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Job description not found"));
        Map<String, Object> input = snapshot.get("input") instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw : Map.of();

        // 候选筛选与 prompt 组装走 MaterialSelectionContextBuilder —— 预览端点用的是同一个类，
        // 所以"预览看到的 prompt"与"这里实际发送的"必然同一份（R32 AC-R32-8）。
        // 候选、强制勾选、被排除项与最终 prompt 一次算清。
        // 预览端点调的是同一个 builder —— 「预览 = 实发」靠的是共用入口，不是靠人工同步两份代码。
        MaterialSelectionContextBuilder.Context ctx = contextBuilder.build(task.getUserId(), job, input);
        // 没有可用候选就不调模型：白烧一次配额，还会让用户拿到一个"AI 说没得选"的假结论。
        if (!ctx.hasCandidates()) {
            return result(List.of(), List.of(), List.of("No eligible career materials"),
                    ctx.excluded(), ctx.manuallyExcluded());
        }
        Set<Long> candidateIds = ctx.candidateIds();
        Set<Long> forced = ctx.forced();
        Map<Long, CareerMaterial> byId = ctx.byId();
        List<CareerMaterial> candidates = ctx.candidates();
        List<CareerMaterial> excluded = ctx.excluded();
        Set<Long> manuallyExcluded = ctx.manuallyExcluded();
        Map<String, Object> providerInput = ctx.providerInput();
        AiCallResult response = providerRegistry.route(AiTaskType.JOB_MATERIAL_SELECTION)
                .call(new AiCallContext(AiTaskType.JOB_MATERIAL_SELECTION, providerInput));
        if (!response.success()) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "Material selection failed: " + response.errorMessage());
        }

        Map<Long, Map<String, Object>> aiRecommendations = parseRecommendations(
                response.data().get("recommended"), candidateIds);
        // 强制勾选优先级最高：先把模型对它的评价整条移除，再统一以 score=100 重新入列。
        // 不这么做，同一个 ID 会带着两套理由并存，前端展示哪一套取决于 map 覆盖顺序。
        forced.forEach(aiRecommendations::remove);
        Map<Long, Map<String, Object>> combinedRecommendations = new LinkedHashMap<>();
        for (Long forcedId : forced) {
            combinedRecommendations.put(forcedId, new LinkedHashMap<>(Map.of(
                    "materialId", forcedId, "relevanceScore", 100,
                    "reason", "Required by user", "matchedRequirements", List.of())));
        }
        // 总数封顶 30：强制项占掉几个名额，AI 推荐就只剩 30-forced 个。
        // 上游已保证 forced ≤ 30，所以这里的 Math.max(0, …) 只用于防御性兜底。
        aiRecommendations.entrySet().stream().limit(Math.max(0, 30 - forced.size()))
                .forEach(entry -> combinedRecommendations.put(entry.getKey(), entry.getValue()));
        List<Map<String, Object>> recommended = combinedRecommendations.values().stream()
                .map(value -> enrich(value, byId.get(longValue(value.get("materialId"))))).toList();
        Set<Long> recommendedIds = recommended.stream().map(v -> longValue(v.get("materialId")))
                .collect(Collectors.toSet());
        List<Map<String, Object>> unselected = candidates.stream()
                .filter(m -> !recommendedIds.contains(m.getId()))
                .map(m -> unselected(m, response.data())).toList();
        List<String> missing = stringList(response.data().get("missingRequirements"));
        return result(recommended, unselected, missing, excluded, manuallyExcluded);
    }

    private Map<String, Object> result(List<Map<String, Object>> recommended,
                                       List<Map<String, Object>> unselected,
                                       List<String> missing, List<CareerMaterial> excluded,
                                       Set<Long> manuallyExcluded) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recommended", recommended);
        result.put("unselected", unselected);
        result.put("missingRequirements", missing);
        result.put("excluded", excluded.stream().map(material -> {
            Map<String, Object> value = summary(material);
            boolean unusable = !CareerMaterialEvidence.isReady(material);
            value.put("exclusionReason", unusable ? "INVALID_EVIDENCE"
                    : manuallyExcluded.contains(material.getId()) ? "MANUAL" : "GLOBAL");
            value.put("reason", unusable
                    ? "缺少来源原文或有意义的结构化内容"
                    : manuallyExcluded.contains(material.getId())
                    ? "Excluded for this generation" : "Excluded by global preference");
            return value;
        }).toList());
        return result;
    }

    /**
     * 解析模型的推荐列表 —— 这里是**唯一**的信任边界。
     *
     * <p>三条硬规则：① 不在候选集合里的 ID 一律丢弃（模型幻觉或快照过期）；
     * ② 最多收 12 条；③ 重复 ID 只保留第一次出现的那条。
     * 任何一条放宽，都会让"用户确认过的候选范围"失效。
     */
    private Map<Long, Map<String, Object>> parseRecommendations(Object raw, Set<Long> candidateIds) {
        if (!(raw instanceof List<?> list)) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "AI selection output has no recommended list");
        }
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Long id = longValue(map.get("materialId"));
            if (id == null || !candidateIds.contains(id)) {
                // Model output is untrusted. Ignore a hallucinated or stale ID
                // rather than letting it escape the confirmed candidate boundary.
                continue;
            }
            if (result.size() >= MAX_RECOMMENDED || result.containsKey(id)) continue;
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("materialId", id);
            normalized.put("relevanceScore", boundedScore(map.get("relevanceScore")));
            normalized.put("reason", Objects.toString(map.get("reason"), "Relevant to the job"));
            normalized.put("matchedRequirements", stringList(map.get("matchedRequirements")));
            result.put(id, normalized);
        }
        return result;
    }

    private Map<String, Object> enrich(Map<String, Object> entry, CareerMaterial material) {
        Map<String, Object> result = new LinkedHashMap<>(entry);
        result.put("title", material.getTitle());
        result.put("materialType", material.getMaterialType().name());
        result.put("usagePreference", material.getUsagePreference().name());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unselected(CareerMaterial material, Map<String, Object> aiData) {
        String reason = "Not selected for this job";
        if (aiData.get("unselected") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && material.getId().equals(longValue(map.get("materialId")))) {
                    reason = Objects.toString(map.get("reason"), reason);
                    break;
                }
            }
        }
        Map<String, Object> result = summary(material);
        result.put("reason", reason);
        return result;
    }

    private Map<String, Object> summary(CareerMaterial material) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("materialId", material.getId());
        value.put("title", material.getTitle());
        value.put("materialType", material.getMaterialType().name());
        value.put("usagePreference", material.getUsagePreference().name());
        return value;
    }

    private int boundedScore(Object value) {
        int score = value instanceof Number n ? n.intValue() : 0;
        return Math.max(0, Math.min(100, score));
    }

    private Long longValue(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return value == null ? null : Long.valueOf(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        return collection.stream().filter(Objects::nonNull).map(Object::toString).toList();
    }
}
