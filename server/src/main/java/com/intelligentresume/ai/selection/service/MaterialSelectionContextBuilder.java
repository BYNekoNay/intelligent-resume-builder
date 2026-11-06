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
 * <p>新人三问：①业务问题：用户资料可能上百条，不可能全发给 AI 让它挑；这里按
 * "用户勾选 + 资料质量 + 与 JD 的文本相关度"筛出最多 60 条候选并拼成 prompt；
 * ②链路位置：被 {@code JobMaterialSelectionService}（worker 执行）和
 * {@code JobMaterialSelectionController#preview}（发送前预览）共同调用；
 * ③改坏它用户会看到：推荐结果里出现用户排除过的资料、每次刷新推荐结果变来变去
 * （排序不稳定）、或空候选时发了一次没意义的模型调用。
 *
 * <p>术语：相关度打分 = 纯字符串匹配算"JD 里出现的词在资料里也出现多少"，不是语义理解，
 * 同义词匹配不上是预期行为（打分只决定排序，不决定能不能用）。
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
 *
 * <p>要点：
 * <ul>
 *   <li>候选筛选顺序：剔除无有效证据 → 剔除手工排除 → 剔除非强制加入的"全局排除" → 打分排序
 *       → 截断到 {@link #MAX_CANDIDATES}（60 条）。打分不改变"能不能用"，但决定截断时谁能留下。</li>
 *   <li>强制加入（included）既参与打分（直接 100000 分顶到最前）也必须留在候选里：截断后
 *       若 included 有遗漏直接 400，而不是静默丢弃 —— 用户明确说过"必须用"。</li>
 *   <li>强制加入超过 30 条直接 400；手工排除的 ID 会先被从 included/preferred 里剔除，
 *       所以"排除"永远赢。</li>
 *   <li>候选为空时不生成 providerInput（三段 prompt 一个都不放）—— 调用方必须自己判断
 *       {@link Context#hasCandidates()}，否则会把空 prompt 发出去。</li>
 *   <li>profile 段只放职业定位类字段：联系方式在 {@code profileContext} 组装时就被挑掉，
 *       模型拿不到邮箱/电话。</li>
 * </ul>
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
     * build 的执行顺序即业务含义，①→⑥：
     * ①解析用户勾选（强制加入 / 优先用 / 手动排除），排除永远赢——先从 forced/preferred 里剔除；
     * ②校验归属与可用性（勾的 ID 必须是本人的资料；"必须用"的资料没证据直接 400）；
     * ③算排除名单（手动排除 + 无有效证据 + 全局偏好排除，但强制加入的除外）；
     * ④候选打分排序（强制加入 10 万分顶到最前 → 优先用 1 万 → 其余按与 JD 的词元重合度加分）
     *    并截断到 60 条——排序带 id 兜底保证同分同秒时顺序也稳定，"每次推荐不一样"就是这么防的；
     * ⑤强制加入若被截掉则 400（用户明确说"必须用"，静默丢弃不可接受）；
     * ⑥拼 providerInput（候选为空时不拼，调用方必须查 {@link Context#hasCandidates()}）。
     *
     * @param job   已解析的岗位（预览用**未持久化**的瞬时对象，worker 用库里的那条 —— 字段相同即同源）
     * @param input 任务快照里的 input：includedMaterialIds / preferredMaterialIds / excludedMaterialIds
     *
     * <p>排序用三个键：分数降序 → updatedAt 降序（null 排最后）→ id 降序。最后那个 id 兜底
     * 不是装饰：少了它，同分同秒的资料顺序不稳定，截断到 60 条时每次丢的可能不是同一批。
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

    /**
     * 把文本切成可比较的词元：英文/数字按非字母数字分隔后取长度 ≥ 2 且含 ASCII 的片段；
     * 中文没有空格，改用 2-gram + 3-gram 滑窗（{@code 数据分析} → {@code 数据}/{@code 据分}/…/{@code 数据分析}），
     * 这样"数据分析师"和"数据分析"能命中同一个 bigram。
     *
     * <p>这是纯字符串匹配，不是语义匹配 —— 同义词/缩写仍然匹配不上，打分只用于排序。
     */
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
