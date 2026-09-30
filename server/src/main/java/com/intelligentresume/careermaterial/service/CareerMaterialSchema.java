package com.intelligentresume.careermaterial.service;

import com.intelligentresume.careermaterial.domain.MaterialType;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * contentJson 最小 schema 的软校验目录（第一阶段：只告警、不拒绝）。
 *
 * <p>背景：13 类资料中仅 ACHIEVEMENT / LEADERSHIP_EXPERIENCE / SKILL_EVIDENCE
 * 有硬校验（{@link CareerMaterialService#validateTypeSpecificContent}），其余类型
 * 长期只有通用证据规则。本目录为这些类型记录「候选 schema」，键取自消费方实测
 * （简历编辑器插材料的读取键、AI 快照清洗器与选材/生成提示词实际使用的键）。
 *
 * <p>软校验规则（三步走的第一步，见
 * {@code docs/plans/2026-09-30-001-career-material-schema-soft-validation.md}）：
 * <ul>
 *     <li>{@code content} 未出现任何 {@code knownKeys}（自由 JSON、成就引导形态、
 *     历史数据）→ 放行且不告警；</li>
 *     <li>出现了 {@code knownKeys} 但没有一个 {@code subjectKeys}（半填形态，
 *     说明调用方在按 schema 填写）→ 记录结构化告警，仍放行；</li>
 *     <li>含任一 {@code subjectKeys} → 视为完整。</li>
 * </ul>
 * 观察期结束后，再据告警数据决定各类型是否升级为硬校验。
 */
public final class CareerMaterialSchema {

    /** 消费方实际读取的键：出现即代表「按 schema 填写」意图。 */
    private static final Map<MaterialType, Set<String>> KNOWN_KEYS = Map.ofEntries(
            Map.entry(MaterialType.WORK_EXPERIENCE, Set.of(
                    "company", "organization", "position", "role", "startDate", "endDate",
                    "period", "description", "summary", "applicationDescription",
                    "responsibilityScope", "outcome", "result", "outcomeEvidence")),
            Map.entry(MaterialType.PROJECT_EXPERIENCE, Set.of(
                    "name", "role", "position", "startDate", "endDate", "period",
                    "description", "summary", "applicationDescription",
                    "responsibilityScope", "outcome", "result", "outcomeEvidence")),
            Map.entry(MaterialType.SKILL, Set.of(
                    "skillName", "name", "category", "proficiency", "yearsOfExperience", "lastUsedAt")),
            Map.entry(MaterialType.EDUCATION, Set.of(
                    "school", "institution", "degree", "major", "area", "startDate", "endDate", "period")),
            Map.entry(MaterialType.CERTIFICATE, Set.of(
                    "name", "title", "issuer", "organization", "date")),
            Map.entry(MaterialType.HIGHLIGHT, Set.of(
                    "description", "summary", "outcome", "result", "outcomeEvidence", "period")),
            Map.entry(MaterialType.AWARD, Set.of(
                    "name", "title", "issuer", "organization", "date", "description")),
            Map.entry(MaterialType.VOLUNTEER_EXPERIENCE, Set.of(
                    "organization", "company", "role", "position", "startDate", "endDate",
                    "period", "description", "summary", "responsibilityScope",
                    "outcome", "result", "outcomeEvidence")),
            Map.entry(MaterialType.COURSE, Set.of(
                    "name", "courseName", "provider", "institution", "date", "description")),
            Map.entry(MaterialType.PUBLICATION, Set.of(
                    "title", "name", "publisher", "issuer", "date", "url", "link", "description")));

    /** 主体键：含任一即视为能构成一条有意义的资料。 */
    private static final Map<MaterialType, Set<String>> SUBJECT_KEYS = Map.ofEntries(
            Map.entry(MaterialType.WORK_EXPERIENCE, Set.of(
                    "company", "organization", "position", "role", "description", "summary")),
            Map.entry(MaterialType.PROJECT_EXPERIENCE, Set.of(
                    "name", "role", "position", "description", "summary")),
            Map.entry(MaterialType.SKILL, Set.of("skillName", "name")),
            Map.entry(MaterialType.EDUCATION, Set.of("school", "institution", "degree")),
            Map.entry(MaterialType.CERTIFICATE, Set.of("name", "title", "issuer")),
            Map.entry(MaterialType.HIGHLIGHT, Set.of(
                    "description", "summary", "outcome", "result", "outcomeEvidence")),
            Map.entry(MaterialType.AWARD, Set.of("name", "title", "issuer", "description")),
            Map.entry(MaterialType.VOLUNTEER_EXPERIENCE, Set.of(
                    "organization", "company", "role", "position", "description", "summary")),
            Map.entry(MaterialType.COURSE, Set.of("name", "courseName", "provider")),
            Map.entry(MaterialType.PUBLICATION, Set.of("title", "name", "publisher", "url")));

    private CareerMaterialSchema() {
    }

    /**
     * content 中已出现且非空的 schema 键；用于告警日志与观察期统计。
     * 未纳入软校验的类型（含已硬校验的三类）恒为空集。
     */
    public static Set<String> presentKnownKeys(MaterialType type, Map<String, Object> content) {
        Set<String> known = KNOWN_KEYS.get(type);
        if (known == null || content == null || content.isEmpty()) {
            return Set.of();
        }
        Set<String> present = new LinkedHashSet<>();
        for (String key : known) {
            if (isPresent(content.get(key))) {
                present.add(key);
            }
        }
        return present;
    }

    /**
     * 半填形态：出现了 schema 键但缺全部主体键。仅此形态触发软校验告警。
     */
    public static boolean isHalfFilled(MaterialType type, Map<String, Object> content) {
        Set<String> present = presentKnownKeys(type, content);
        if (present.isEmpty()) {
            return false;
        }
        Set<String> subject = SUBJECT_KEYS.get(type);
        if (subject == null) {
            return false;
        }
        return subject.stream().noneMatch(present::contains);
    }

    private static boolean isPresent(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        return true;
    }
}