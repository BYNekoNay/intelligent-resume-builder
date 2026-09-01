package com.intelligentresume.ai.selection.service;

import com.intelligentresume.ai.provider.AiCallContext;
import com.intelligentresume.ai.provider.AiCallResult;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
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

    private static final int MAX_CANDIDATES = 60;
    private static final int MAX_RECOMMENDED = 12;
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}+#.]+");
    private static final Pattern CJK_SEQUENCE = Pattern.compile("[\\p{IsHan}]+");

    private final CareerMaterialRepository materialRepository;
    private final JobDescriptionRepository jobRepository;
    private final PersonalProfileRepository profileRepository;
    private final MaterialSelectionPromptBuilder promptBuilder;
    private final AiProviderRegistry providerRegistry;

    public JobMaterialSelectionService(CareerMaterialRepository materialRepository,
                                       JobDescriptionRepository jobRepository,
                                       PersonalProfileRepository profileRepository,
                                       MaterialSelectionPromptBuilder promptBuilder,
                                       AiProviderRegistry providerRegistry) {
        this.materialRepository = materialRepository;
        this.jobRepository = jobRepository;
        this.profileRepository = profileRepository;
        this.promptBuilder = promptBuilder;
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
        Set<Long> manuallyExcluded = longSet(input.get("excludedMaterialIds"));
        Set<Long> forced = longSet(input.get("includedMaterialIds"));
        forced.removeAll(manuallyExcluded);
        if (forced.size() > 30) {
            throw new BusinessException(ErrorCode.VALIDATION, "At most 30 materials can be required");
        }
        Set<Long> preferred = longSet(input.get("preferredMaterialIds"));
        preferred.removeAll(manuallyExcluded);

        List<CareerMaterial> all = materialRepository.findByUserIdOrderByUpdatedAtDesc(task.getUserId());
        Map<Long, CareerMaterial> byId = all.stream().collect(Collectors.toMap(CareerMaterial::getId, m -> m));
        validateOwned(forced, byId);
        validateOwned(preferred, byId);

        List<CareerMaterial> excluded = all.stream()
                .filter(m -> manuallyExcluded.contains(m.getId())
                        || (m.getUsagePreference() == UsagePreference.EXCLUDED && !forced.contains(m.getId())))
                .toList();
        Set<String> jobTokens = tokens(job.getTitle() + " " + job.getJdText());
        List<CareerMaterial> candidates = all.stream()
                .filter(m -> !manuallyExcluded.contains(m.getId()))
                .filter(m -> m.getUsagePreference() != UsagePreference.EXCLUDED || forced.contains(m.getId()))
                .sorted(Comparator.<CareerMaterial>comparingInt(m -> score(m, jobTokens, forced, preferred)).reversed()
                        .thenComparing(CareerMaterial::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_CANDIDATES)
                .toList();
        Set<Long> candidateIds = candidates.stream().map(CareerMaterial::getId).collect(Collectors.toSet());
        if (!candidateIds.containsAll(forced)) {
            throw new BusinessException(ErrorCode.VALIDATION, "Too many required materials; candidate limit exceeded");
        }
        if (candidates.isEmpty()) {
            return result(List.of(), List.of(), List.of("No eligible career materials"), excluded,
                    manuallyExcluded);
        }

        Map<String, Object> profileContext = profileRepository.findByUserId(task.getUserId())
                .map(this::profileContext).orElseGet(Map::of);
        MaterialSelectionPromptBuilder.Prompt prompt = promptBuilder.build(job, candidates,
                new ArrayList<>(forced), profileContext);
        Map<String, Object> providerInput = new LinkedHashMap<>();
        providerInput.put("_systemPrompt", prompt.system());
        providerInput.put("_taskPrompt", prompt.task());
        providerInput.put("_dataPrompt", prompt.data());
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
            value.put("exclusionReason", manuallyExcluded.contains(material.getId()) ? "MANUAL" : "GLOBAL");
            value.put("reason", manuallyExcluded.contains(material.getId())
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

    private int score(CareerMaterial material, Set<String> jobTokens, Set<Long> forced, Set<Long> preferred) {
        if (forced.contains(material.getId())) return 100_000;
        int score = preferred.contains(material.getId()) || material.getUsagePreference() == UsagePreference.PREFERRED
                ? 10_000 : 0;
        Set<String> materialTokens = tokens(material.getTitle() + " "
                + Objects.toString(material.getSourceText(), "") + " "
                + Objects.toString(material.getContentJson(), ""));
        for (String token : materialTokens) {
            if (jobTokens.contains(token)) score += Math.min(100, token.length() * 5);
        }
        return score;
    }

    private Set<String> tokens(String text) {
        String normalized = Objects.toString(text, "").toLowerCase(Locale.ROOT);
        Set<String> result = Arrays.stream(TOKEN_SPLIT.split(normalized))
                .filter(token -> token.length() >= 2 && token.chars().anyMatch(ch -> ch < 128))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Matcher matcher = CJK_SEQUENCE.matcher(normalized);
        while (matcher.find()) {
            String sequence = matcher.group();
            for (int size : List.of(2, 3)) {
                for (int i = 0; i + size <= sequence.length(); i++) {
                    result.add(sequence.substring(i, i + size));
                }
            }
        }
        return result;
    }

    /** Excludes all contact details: the model only receives career-positioning facts. */
    private Map<String, Object> profileContext(PersonalProfile profile) {
        Map<String, Object> context = new LinkedHashMap<>();
        putNonBlank(context, "profileSummary", profile.getProfileSummary());
        putNonEmpty(context, "targetRoleTitles", profile.getTargetRoleTitles());
        putNonBlank(context, "targetSeniority", profile.getTargetSeniority());
        putNonEmpty(context, "targetIndustries", profile.getTargetIndustries());
        putNonEmpty(context, "targetWorkPreferences", profile.getTargetWorkPreferences());
        putNonBlank(context, "careerPositioningSummary", profile.getCareerPositioningSummary());
        return context;
    }

    private void putNonBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    private void putNonEmpty(Map<String, Object> target, String key, Collection<String> value) {
        if (value != null && !value.isEmpty()) target.put(key, value);
    }

    private void validateOwned(Set<Long> ids, Map<Long, CareerMaterial> byId) {
        if (!byId.keySet().containsAll(ids)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Career material not found");
        }
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
