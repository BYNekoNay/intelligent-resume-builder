package com.intelligentresume.ai.selection.service;

import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.careermaterial.service.CareerMaterialEvidence;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.personalprofile.domain.PersonalProfile;
import com.intelligentresume.personalprofile.repository.PersonalProfileRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 选材任务的「候选筛选 + prompt 组装」—— worker 与预览**共用同一实现**（R32 AC-R32-8）。
 *
 * <p>为什么单独成类：
 * <ol>
 *   <li>{@code JobMaterialSelectionService} 已 298 行，距 300 行棘轮只剩 2 行，装不下预览；
 *   </li>
 *   <li>预览与实际发送必须同源 —— 两处各写一遍候选筛选，必然出现"预览里有的素材实际没发"。
 * </li>
 * </ol>
 *
 * <p>本类**只读**：不写库、不调 provider。worker 拿 {@link Context#providerInput()} 去调用，
 * 预览拿同一份 map 去渲染，因此两边内容逐字节相同。
 */
@Component
public class MaterialSelectionContextBuilder {

    static final int MAX_CANDIDATES = 60;

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern CJK_SEQUENCE = Pattern.compile("[\\u4e00-\\u9fff]+");

    private final CareerMaterialRepository materialRepository;
    private final PersonalProfileRepository profileRepository;
    private final MaterialSelectionPromptBuilder promptBuilder;

    public MaterialSelectionContextBuilder(CareerMaterialRepository materialRepository,
                                           PersonalProfileRepository profileRepository,
                                           MaterialSelectionPromptBuilder promptBuilder) {
        this.materialRepository = materialRepository;
        this.profileRepository = profileRepository;
        this.promptBuilder = promptBuilder;
    }

    /** 一次筛选的全部产物；worker 用它算推荐，预览只用 providerInput。 */
    public record Context(Map<String, Object> providerInput,
                          List<CareerMaterial> candidates,
                          Set<Long> candidateIds,
                          Map<Long, CareerMaterial> byId,
                          Set<Long> forced,
                          Set<Long> preferred,
                          List<CareerMaterial> excluded,
                          Set<Long> manuallyExcluded) {

        public boolean hasCandidates() {
            return !candidates.isEmpty();
        }
    }

    /**
     * @param job   已解析的岗位（预览用**未持久化**的瞬时对象，worker 用库里的那条 —— 字段相同即同源）
     * @param input 任务快照里的 input：includedMaterialIds / preferredMaterialIds / excludedMaterialIds
     */
    public Context build(Long userId, JobDescription job, Map<String, Object> input) {
        Map<String, Object> safeInput = input != null ? input : Map.of();
        Set<Long> manuallyExcluded = longSet(safeInput.get("excludedMaterialIds"));
        Set<Long> forced = longSet(safeInput.get("includedMaterialIds"));
        forced.removeAll(manuallyExcluded);
        if (forced.size() > 30) {
            throw new BusinessException(ErrorCode.VALIDATION, "At most 30 materials can be required");
        }
        Set<Long> preferred = longSet(safeInput.get("preferredMaterialIds"));
        preferred.removeAll(manuallyExcluded);

        List<CareerMaterial> all = materialRepository.findByUserIdOrderByUpdatedAtDescIdDesc(userId);
        Map<Long, CareerMaterial> byId = all.stream().collect(Collectors.toMap(CareerMaterial::getId, m -> m));
        validateOwned(forced, byId);
        validateOwned(preferred, byId);

        Set<Long> unusableIds = all.stream()
                .filter(material -> !CareerMaterialEvidence.isReady(material))
                .map(CareerMaterial::getId)
                .collect(Collectors.toSet());
        if (!java.util.Collections.disjoint(forced, unusableIds)) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "必须使用的资料需要包含来源原文或有意义的结构化内容");
        }

        List<CareerMaterial> excluded = all.stream()
                .filter(m -> manuallyExcluded.contains(m.getId())
                        || unusableIds.contains(m.getId())
                        || (m.getUsagePreference() == UsagePreference.EXCLUDED && !forced.contains(m.getId())))
                .toList();
        Set<String> jobTokens = tokens(job.getTitle() + " " + job.getJdText());
        List<CareerMaterial> candidates = all.stream()
                .filter(m -> !unusableIds.contains(m.getId()))
                .filter(m -> !manuallyExcluded.contains(m.getId()))
                .filter(m -> m.getUsagePreference() != UsagePreference.EXCLUDED || forced.contains(m.getId()))
                .sorted(Comparator.<CareerMaterial>comparingInt(m -> score(m, jobTokens, forced, preferred)).reversed()
                        .thenComparing(CareerMaterial::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(CareerMaterial::getId, Comparator.reverseOrder()))
                .limit(MAX_CANDIDATES)
                .toList();
        Set<Long> candidateIds = candidates.stream().map(CareerMaterial::getId).collect(Collectors.toSet());
        if (!candidateIds.containsAll(forced)) {
            throw new BusinessException(ErrorCode.VALIDATION, "Too many required materials; candidate limit exceeded");
        }

        Map<String, Object> providerInput = new LinkedHashMap<>();
        if (!candidates.isEmpty()) {
            Map<String, Object> profileContext = profileRepository.findByUserId(userId)
                    .map(this::profileContext).orElseGet(Map::of);
            MaterialSelectionPromptBuilder.Prompt prompt = promptBuilder.build(job, candidates,
                    new ArrayList<>(forced), profileContext);
            providerInput.put("_systemPrompt", prompt.system());
            providerInput.put("_taskPrompt", prompt.task());
            providerInput.put("_dataPrompt", prompt.data());
        }
        return new Context(providerInput, candidates, candidateIds, byId, forced, preferred, excluded, manuallyExcluded);
    }

    /** 任务类型固定为选材 —— 预览方不需要再传。 */
    public AiTaskType taskType() {
        return AiTaskType.JOB_MATERIAL_SELECTION;
    }

    // ------------------------------------------------------------------ 内部

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

    private Set<Long> longSet(Object value) {
        if (!(value instanceof Collection<?> collection)) return new LinkedHashSet<>();
        return collection.stream().map(this::longValue).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Long longValue(Object value) {
        if (value instanceof Number n) return n.longValue();
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
