package com.intelligentresume.ai.selection.service;

import com.intelligentresume.ai.provider.AiCallContext;
import com.intelligentresume.ai.provider.AiCallResult;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.careermaterial.service.CareerMaterialEvidence;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.personalprofile.repository.PersonalProfileRepository;
import com.intelligentresume.personalprofile.domain.PersonalProfile;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

@Service
public class JobMaterialSelectionService {

    private static final int MAX_RECOMMENDED = 12;

    private final CareerMaterialRepository materialRepository;
    private final JobDescriptionRepository jobRepository;
    private final PersonalProfileRepository profileRepository;
    private final MaterialSelectionContextBuilder contextBuilder;
    private final AiProviderRegistry providerRegistry;

    public JobMaterialSelectionService(CareerMaterialRepository materialRepository,
                                       JobDescriptionRepository jobRepository,
                                       PersonalProfileRepository profileRepository,
                                       MaterialSelectionContextBuilder contextBuilder,
                                       AiProviderRegistry providerRegistry) {
        this.materialRepository = materialRepository;
        this.jobRepository = jobRepository;
        this.profileRepository = profileRepository;
        this.contextBuilder = contextBuilder;
        this.providerRegistry = providerRegistry;
    }

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
        MaterialSelectionContextBuilder.Context ctx = contextBuilder.build(task.getUserId(), job, input);
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
        forced.forEach(aiRecommendations::remove);
        Map<Long, Map<String, Object>> combinedRecommendations = new LinkedHashMap<>();
        for (Long forcedId : forced) {
            combinedRecommendations.put(forcedId, new LinkedHashMap<>(Map.of(
                    "materialId", forcedId, "relevanceScore", 100,
                    "reason", "Required by user", "matchedRequirements", List.of())));
        }
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

    private Set<Long> longSet(Object value) {
        if (!(value instanceof Collection<?> collection)) return new LinkedHashSet<>();
        return collection.stream().map(this::longValue).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        return collection.stream().filter(Objects::nonNull).map(Object::toString).toList();
    }
}
