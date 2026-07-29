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
 */
@Service
public class CareerMaterialService {

    private static final int MAX_SEARCH_PAGE_SIZE = 100;
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

    @Transactional
    public CareerMaterialDetail create(CreateCareerMaterialRequest req, Long userId) {
        validateContentJsonSize(req.contentJson());
        validateTypeSpecificContent(req.materialType(), req.contentJson(), userId);

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
        return repository.findSummaries(userId, filter);
    }

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

    @Transactional
    public CareerMaterialDetail update(Long id, UpdateCareerMaterialRequest req, Long userId) {
        CareerMaterial material = findOwned(id, userId);
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
        repository.save(material);
        return toDetail(material);
    }

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

    /**
     * Validates the structured fields introduced for reusable career assets.
     * Related material IDs are resolved through the current user to prevent
     * cross-user links as well as links to unrelated material categories.
     */
    private void validateTypeSpecificContent(MaterialType type, Map<String, Object> content, Long userId) {
        if (content == null) {
            throw validation("contentJson is required");
        }
        switch (type) {
            case ACHIEVEMENT -> validateAchievement(content, userId);
            case LEADERSHIP_EXPERIENCE -> validateLeadership(content, userId);
            case SKILL_EVIDENCE -> validateSkillEvidence(content, userId);
            default -> {
                // Existing material types remain backward compatible.
            }
        }
    }

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
                material.getUsagePreference(), material.getUpdatedAt(), excerpt(material));
    }

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

    private String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        return query.trim()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private Sort searchSort(String sortValue) {
        return SEARCH_SORTS.get(sortValue).and(Sort.by(Sort.Direction.ASC, "id"));
    }

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
                m.getCreatedAt(), m.getUpdatedAt());
    }
}
