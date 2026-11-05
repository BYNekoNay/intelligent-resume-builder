package com.intelligentresume.careermaterial.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.careermaterial.dto.*;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 职业资料 CRUD + 类型过滤 + 引用偏好。
 *
 * <p>关键约定:
 * <ul>
 *     <li>所有查询带 userId 条件,杜绝跨用户访问。</li>
 *     <li>软删设置 deleted_at;被历史快照引用的资料删除后快照仍可读。</li>
 *     <li>contentJson 字节数不超过 {@code app.career-material.content-json.max-bytes}。</li>
 * </ul>
 *
 * <p>实现要点：
 * <ul>
 *     <li>所有入口先拿 {@code findOwned(id, userId)}，跨用户与不存在<b>同样</b>返回 NOT_FOUND，
 *         不区分两者，避免用状态码探测资料是否存在；</li>
 *     <li>写路径的校验顺序固定为「字节上限 → 类型必填 → 证据可用」；调整顺序会改变用户
 *         看到的第一个报错，前端依赖该信息定位字段，勿随意重排；</li>
 *     <li>关联资料（{@code relatedMaterialId(s)}）必须经本用户查回并限定为
 *         WORK_EXPERIENCE / PROJECT_EXPERIENCE，既防越权也防挂到不相干的类型上；</li>
 *     <li>列表走读模型投影、搜索走包含扫描，两条路径的 evidenceReady 都复用
 *         {@link CareerMaterialEvidence}，不要各写一份；</li>
 *     <li>搜索词长度与分页大小是硬上限（100），超出直接报错而不是静默截断，
 *         因为静默截断会让用户以为搜到了全部结果。</li>
 * </ul>
 */
@Service
public class CareerMaterialService {

    private static final Logger log = LoggerFactory.getLogger(CareerMaterialService.class);

    private static final int MAX_SEARCH_PAGE_SIZE = 100;
    /**
     * 搜索词长度上限（ideation #72）：搜索对 title/sourceText/contentJson 做
     * 无索引包含扫描，长词会线性放大每次请求的字符串处理与扫描成本。
     */
    private static final int MAX_SEARCH_QUERY_LENGTH = 100;
    private static final int EXCERPT_MAX_LENGTH = 180;
    private static final Map<String, Sort> SEARCH_SORTS = Map.of(
            "updatedAt,desc", Sort.by(Sort.Direction.DESC, "updatedAt"),
            "updatedAt,asc", Sort.by(Sort.Direction.ASC, "updatedAt"),
            "title,asc", Sort.by(Sort.Direction.ASC, "title"));

    private final CareerMaterialRepository repository;
    private final ObjectMapper objectMapper;
    private final int maxContentBytes;

    public CareerMaterialService(CareerMaterialRepository repository,
                                 ObjectMapper objectMapper,
                                 @Value("${app.career-material.content-json.max-bytes:65536}") int maxContentBytes) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.maxContentBytes = maxContentBytes;
    }

    /**
     * 新建资料。
     *
     * <p>校验顺序：字节上限 → 类型必填 → 证据可用。三者针对不同失败原因，
     * 顺序决定了用户先看到哪条错误，改动前先确认前端提示文案的依赖。
     * usagePreference 缺省填 NORMAL，不依赖库默认值。
     */
    @Transactional
    public CareerMaterialDetail create(CreateCareerMaterialRequest req, Long userId) {
        validateContentJsonSize(req.contentJson());
        validateTypeSpecificContent(req.materialType(), req.contentJson(), userId);
        validateMeaningfulEvidence(req.sourceText(), req.contentJson());

        CareerMaterial material = new CareerMaterial();
        material.setUserId(userId);
        material.setMaterialType(req.materialType());
        material.setTitle(req.title());
        material.setContentJson(req.contentJson());
        material.setSourceText(req.sourceText());
        material.setUsagePreference(req.usagePreference() != null ? req.usagePreference() : UsagePreference.NORMAL);
        repository.save(material);
        return toDetail(material);
    }

    @Transactional(readOnly = true)
    public List<CareerMaterialSummary> list(Long userId, MaterialType filter) {
        // 读模型投影（ideation #1）：不读 MEDIUMTEXT 原文，类型过滤在 SQL 完成；
        // evidenceReady 与详情/搜索路径共用 CareerMaterialEvidence 规则。
        return repository.findListRowsByUserId(userId, filter).stream()
                .map(row -> new CareerMaterialSummary(
                        row.id(), row.materialType(), row.title(), row.usagePreference(), row.updatedAt(),
                        CareerMaterialEvidence.isReady(row.hasSourceText(), row.contentJson())))
                .toList();
    }

    /**
     * 搜索：标题 / 原文 / contentJson 三列包含匹配，可叠加类型与使用偏好过滤。
     *
     * <p>注意三点：
     * <ul>
     *   <li>入参校验是"拒绝"而非"纠正"：page/size/sort 越界直接报错；</li>
     *   <li>typeCounts 是<b>全量</b>按类型计数，不随 q / type / usagePreference 变化，
     *       供筛选标签展示总数；改成分页内计数会让徽标随翻页跳变；</li>
     *   <li>query 已做 LIKE 通配符转义，与仓储层 {@code escape '\\'} 必须成对存在。</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public CareerMaterialSearchPage search(Long userId, String query, MaterialType type,
                                           UsagePreference usagePreference, int page, int size,
                                           String sortValue) {
        validateSearchParameters(page, size, sortValue);
        String normalizedQuery = normalizeQuery(query);
        Page<CareerMaterial> result = repository.search(
                userId, type, usagePreference, normalizedQuery,
                PageRequest.of(page, size, searchSort(sortValue)));

        Map<MaterialType, Long> typeCounts = new EnumMap<>(MaterialType.class);
        repository.countByType(userId)
                .forEach(count -> typeCounts.put(count.materialType(), count.count()));

        return new CareerMaterialSearchPage(
                result.getContent().stream().map(this::toSearchItem).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), typeCounts);
    }

    @Transactional(readOnly = true)
    public CareerMaterialDetail get(Long id, Long userId) {
        return toDetail(findOwned(id, userId));
    }

    /**
     * 部分更新。字段为 null 视为"不改"，因此要先合并出 next 值再校验，
     * 不能只看本次请求体：例如单独改 usagePreference 时，证据校验用的是库里的旧原文/旧 JSON。
     *
     * <p>两处易错：
     * <ul>
     *   <li>title 空白串会被<b>忽略</b>（保留旧标题），而 sourceText 空串会真的清空原文，
     *       两者语义不一致，前端做"清空"操作前需确认；</li>
     *   <li>类型取自库里的 materialType，请求无法改类型，故校验针对旧类型执行。</li>
     * </ul>
     */
    @Transactional
    public CareerMaterialDetail update(Long id, UpdateCareerMaterialRequest req, Long userId) {
        CareerMaterial material = findOwned(id, userId);
        String nextSourceText = req.sourceText() != null ? req.sourceText() : material.getSourceText();
        Map<String, Object> nextContentJson = req.contentJson() != null ? req.contentJson() : material.getContentJson();
        validateMeaningfulEvidence(nextSourceText, nextContentJson);
        if (req.title() != null && !req.title().isBlank()) {
            material.setTitle(req.title());
        }
        if (req.contentJson() != null) {
            validateContentJsonSize(req.contentJson());
            validateTypeSpecificContent(material.getMaterialType(), req.contentJson(), userId);
            material.setContentJson(req.contentJson());
        }
        if (req.sourceText() != null) {
            material.setSourceText(req.sourceText());
        }
        if (req.usagePreference() != null) {
            material.setUsagePreference(req.usagePreference());
        }
        // saveAndFlush：版本冲突（@Version）在服务内即抛，由全局处理器映射为 40901，
        // 而不是延迟到事务提交点。
        repository.saveAndFlush(material);
        return toDetail(material);
    }

    /** 软删：只置 deleted_at，实体仍留在库里，供历史快照与引用关系继续解析。 */
    @Transactional
    public void softDelete(Long id, Long userId) {
        CareerMaterial material = findOwned(id, userId);
        material.setDeletedAt(LocalDateTime.now());
        repository.save(material);
    }

    // ---- helpers ----

    private CareerMaterial findOwned(Long id, Long userId) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "资料不存在"));
    }

    /** 按 JSON 序列化后的实际字节数判上限（不是字符数），序列化失败也归为参数错误。 */
    private void validateContentJsonSize(Object contentJson) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(contentJson);
            if (bytes.length > maxContentBytes) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "contentJson 超过大小限制 (" + maxContentBytes + " bytes)");
            }
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "contentJson 序列化失败");
        }
    }

    private void validateMeaningfulEvidence(String sourceText, Map<String, Object> contentJson) {
        if (!CareerMaterialEvidence.hasMeaningfulEvidence(sourceText, contentJson)) {
            throw validation("资料必须包含来源原文或有意义的结构化内容");
        }
    }

    /**
     * Validates the structured fields introduced for reusable career assets.
     * Related material IDs are resolved through the current user to prevent
     * cross-user links as well as links to unrelated material categories.
     *
     * <p>Only ACHIEVEMENT / LEADERSHIP_EXPERIENCE / SKILL_EVIDENCE have hard
     * validation today. The remaining types pass through a soft schema check
     * that warns (without rejecting) on half-filled structured content; see
     * {@link CareerMaterialSchema} and the three-step migration plan in
     * {@code docs/plans/2026-09-30-001-career-material-schema-soft-validation.md}.
     *
     * <p>中文说明：三类走硬校验（缺字段即拒绝），其余类型只走软校验（半填形态记 warn 日志）。
     * 新增硬校验类型必须同时改这里和 {@link CareerMaterialSchema}，否则同一类型会同时
     * 出现在两套规则里，行为取决于 switch 是否覆盖到。
     */
    private void validateTypeSpecificContent(MaterialType type, Map<String, Object> content, Long userId) {
        if (content == null) {
            throw validation("contentJson is required");
        }
        switch (type) {
            case ACHIEVEMENT -> validateAchievement(content, userId);
            case LEADERSHIP_EXPERIENCE -> validateLeadership(content, userId);
            case SKILL_EVIDENCE -> validateSkillEvidence(content, userId);
            default -> warnOnHalfFilledContent(type, content);
        }
    }

    /**
     * 软校验（只告警不拒绝）：content 出现了类型 schema 的已知键但缺全部主体键时，
     * 记录结构化告警供观察期统计。自由 JSON 与历史形态（未出现任何 schema 键）
     * 不告警——它们仍是合法形态，待观察期数据后再决定是否升级硬校验。
     */
    private void warnOnHalfFilledContent(MaterialType type, Map<String, Object> content) {
        if (CareerMaterialSchema.isHalfFilled(type, content)) {
            log.warn("Career material contentJson soft-schema warning: materialType={}, presentKnownKeys={}",
                    type, CareerMaterialSchema.presentKnownKeys(type, content));
        }
    }

    /**
     * 成就类：先锁死归属关系，再按 metricDisplayMode 决定"量化值"字段哪个必填。
     * EXACT 要 metricExactValue；RANGE/QUALITATIVE 都要 metricDisplayValue。
     * 只改三选一的枚举值而不改字段名，会让已有的历史数据在新规则下变成非法。
     */
    private void validateAchievement(Map<String, Object> content, Long userId) {
        validateRelatedExperience(requiredId(content, "relatedMaterialId"), userId, "relatedMaterialId");
        requireText(content, "scenario");
        requireText(content, "action");
        requireText(content, "outcome");
        requireText(content, "period");
        requireText(content, "metricName");
        String mode = requireText(content, "metricDisplayMode");
        if (!Set.of("EXACT", "RANGE", "QUALITATIVE").contains(mode)) {
            throw validation("metricDisplayMode must be EXACT, RANGE, or QUALITATIVE");
        }
        switch (mode) {
            case "EXACT" -> requireText(content, "metricExactValue");
            case "RANGE" -> requireText(content, "metricDisplayValue");
            case "QUALITATIVE" -> requireText(content, "metricDisplayValue");
            default -> throw validation("Unsupported metricDisplayMode");
        }
    }

    private void validateLeadership(Map<String, Object> content, Long userId) {
        validateRelatedExperience(requiredId(content, "relatedMaterialId"), userId, "relatedMaterialId");
        requireText(content, "responsibilityScope");
        requireText(content, "collaborationTargets");
        requireText(content, "teamSize");
        requireText(content, "crossFunctionalRelationship");
        requireText(content, "keyDecision");
        requireText(content, "result");
    }

    /**
     * 技能证据：七个文本字段全必填；{@code relatedMaterialIds} 是<b>可选</b>的
     * （缺省直接放行），一旦给了就必须是非空数组且每个 id 都过归属+类型校验。
     * 注意数组里出现 null 或 0/负数会被 {@link #toId} 拒掉。
     */
    private void validateSkillEvidence(Map<String, Object> content, Long userId) {
        requireText(content, "skillName");
        requireText(content, "category");
        requireText(content, "proficiency");
        requireText(content, "yearsOfExperience");
        requireText(content, "lastUsedAt");
        requireText(content, "applicationDescription");
        requireText(content, "outcomeEvidence");

        Object relatedValues = content.get("relatedMaterialIds");
        if (relatedValues == null) {
            return;
        }
        if (!(relatedValues instanceof Collection<?> relatedIds)) {
            throw validation("relatedMaterialIds must be an array");
        }
        for (Object relatedId : relatedIds) {
            validateRelatedExperience(toId(relatedId, "relatedMaterialIds"), userId, "relatedMaterialIds");
        }
    }

    /**
     * 关联资料的两道门槛：必须属于当前用户，且只能是工作或项目经历——
     * 其他类型没有"时间段/上下文"，挂上去会产生无法解释的引用。
     */
    private void validateRelatedExperience(Long materialId, Long userId, String field) {
        CareerMaterial related = repository.findByIdAndUserId(materialId, userId)
                .orElseThrow(() -> validation(field + " must reference one of your materials"));
        if (related.getMaterialType() != MaterialType.WORK_EXPERIENCE
                && related.getMaterialType() != MaterialType.PROJECT_EXPERIENCE) {
            throw validation(field + " must reference WORK_EXPERIENCE or PROJECT_EXPERIENCE");
        }
    }

    private Long requiredId(Map<String, Object> content, String field) {
        if (!content.containsKey(field)) {
            throw validation(field + " is required");
        }
        return toId(content.get(field), field);
    }

    /**
     * 兼容 JSON 反序列化后的两种形态：数字（前端传 int/long）与数字字符串
     * （表单/旧数据）。只接受正数 ID；解析失败统一报同一条用户可读错误，
     * 不把 NumberFormatException 抛出去。
     */
    private Long toId(Object value, String field) {
        if (value instanceof Number number && number.longValue() > 0) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                long id = Long.parseLong(text.trim());
                if (id > 0) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
                // Report the same user-facing validation error below.
            }
        }
        throw validation(field + " must be a positive material ID");
    }

    private String requireText(Map<String, Object> content, String field) {
        Object value = content.get(field);
        if (value instanceof String text && !text.trim().isEmpty()) {
            return text.trim();
        }
        throw validation(field + " is required");
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private CareerMaterialSearchItem toSearchItem(CareerMaterial material) {
        return new CareerMaterialSearchItem(
                material.getId(), material.getMaterialType(), material.getTitle(),
                material.getUsagePreference(), material.getUpdatedAt(), excerpt(material),
                CareerMaterialEvidence.isReady(material));
    }

    /** sort 走白名单而非字符串拼接：外部传入的排序字段只能落在 {@code SEARCH_SORTS} 内。 */
    private void validateSearchParameters(int page, int size, String sortValue) {
        if (page < 0) {
            throw validation("page must be greater than or equal to 0");
        }
        if (size < 1 || size > MAX_SEARCH_PAGE_SIZE) {
            throw validation("size must be between 1 and " + MAX_SEARCH_PAGE_SIZE);
        }
        if (!SEARCH_SORTS.containsKey(sortValue)) {
            throw validation("unsupported sort: " + sortValue);
        }
    }

    /**
     * 归一化搜索词：空白视为"无关键词"（返回 null，SQL 侧跳过 like 条件）；
     * 超过长度上限直接拒绝；反斜杠先转义，再转义 {@code %} 与 {@code _}，
     * 顺序不能反，否则会把自己插入的转义符再转一次。
     */
    private String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String trimmed = query.trim();
        if (trimmed.length() > MAX_SEARCH_QUERY_LENGTH) {
            throw validation("query must be at most " + MAX_SEARCH_QUERY_LENGTH + " characters");
        }
        return trimmed
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /** 用户选定的排序 + id 升序兜底：同毫秒更新的多行必须稳定，否则翻页会重复/漏行。 */
    private Sort searchSort(String sortValue) {
        return SEARCH_SORTS.get(sortValue).and(Sort.by(Sort.Direction.ASC, "id"));
    }

    /**
     * 摘要片段：有原文就用原文；没有则按类型挑 contentJson 里的代表性字段拼接。
     * 未列出的类型返回空串（不是异常），新增类型若想让搜索卡片有摘要，需要在此补分支。
     */
    private String excerpt(CareerMaterial material) {
        String sourceText = normalizeExcerptPart(material.getSourceText());
        if (!sourceText.isEmpty()) {
            return truncate(sourceText);
        }

        Map<String, Object> content = material.getContentJson();
        if (content == null) {
            return "";
        }
        List<String> parts = switch (material.getMaterialType()) {
            case ACHIEVEMENT -> List.of(
                    contentText(content, "outcome"),
                    firstNonBlank(contentText(content, "metricDisplayValue"),
                            contentText(content, "metricExactValue")));
            case LEADERSHIP_EXPERIENCE -> List.of(
                    contentText(content, "responsibilityScope"), contentText(content, "result"));
            case SKILL_EVIDENCE -> List.of(
                    contentText(content, "skillName"), contentText(content, "outcomeEvidence"));
            default -> List.of();
        };
        return truncate(parts.stream().filter(part -> !part.isEmpty()).collect(java.util.stream.Collectors.joining(" · ")));
    }

    private String contentText(Map<String, Object> content, String key) {
        Object value = content.get(key);
        return value instanceof String text ? normalizeExcerptPart(text) : "";
    }

    private String normalizeExcerptPart(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String firstNonBlank(String first, String second) {
        return first.isEmpty() ? second : first;
    }

    private String truncate(String value) {
        if (value.length() <= EXCERPT_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, EXCERPT_MAX_LENGTH - 3).stripTrailing() + "...";
    }

    private CareerMaterialDetail toDetail(CareerMaterial m) {
        return new CareerMaterialDetail(m.getId(), m.getMaterialType(), m.getTitle(),
                m.getContentJson(), m.getSourceText(), m.getUsagePreference(),
                m.getCreatedAt(), m.getUpdatedAt(), CareerMaterialEvidence.isReady(m));
    }
}
