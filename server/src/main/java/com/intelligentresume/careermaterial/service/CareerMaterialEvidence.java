package com.intelligentresume.careermaterial.service;

import com.intelligentresume.careermaterial.domain.CareerMaterial;

import java.util.Collection;
import java.util.Map;

/**
 * 判断"一条素材够不够格被用"的唯一裁判：有来源原文（sourceText 非空）或
 * 有至少一个有意义的结构化字段，就算"证据就绪"（evidenceReady）。
 * 没就绪的素材在界面上会被标"资料不足"，AI 选材时也不该依赖它。
 *
 * <p>链路位置：创建/更新时是"写闸门"（不达标拒绝保存），列表/详情/搜索时是"读标记"
 * （算出 evidenceReady 给前端）。全项目就这一处判定，别处另写会口径漂移。
 *
 * <p>Shared evidence-quality boundary for career materials.
 *
 * <p>A title is a label, not evidence. A material is usable when it has
 * non-blank source text or at least one meaningful structured value. The
 * check intentionally stays generic so legacy material types can continue to
 * use their existing JSON shape, while specialized types apply their own
 * required-field validation in {@link CareerMaterialService}.</p>
 *
 * <p>中文要点：
 * <ul>
 *   <li>唯一判定口径：详情、列表、搜索三条读路径都必须调本类，不要在前端或 SQL 里
 *       另写一份"是否有内容"的判断；</li>
 *   <li>标题不算证据：递归遍历 contentJson 时会跳过键名为 {@code title} 的条目
 *       （大小写不敏感），否则"只有标题"的空资料会被误判为可用；</li>
 *   <li>数值恒为有意义（含 0）、布尔取值为 {@code true} 才算有意义（{@code false} 不算），
 *       空串/纯空白串不算；</li>
 *   <li>列表路径传的是 SQL 算出的 {@code hasSourceText}，与 Java 的 {@code isBlank()}
 *       存在细微差异（SQL 只 trim 空白，不识别换行/制表符-only），属既定取舍。</li>
 * </ul>
 */
public final class CareerMaterialEvidence {

    private CareerMaterialEvidence() {
    }

    /** 实体版入口：null 实体直接视为未就绪，避免调用方各处判空。 */
    public static boolean isReady(CareerMaterial material) {
        return material != null && hasMeaningfulEvidence(material.getSourceText(), material.getContentJson());
    }

    /**
     * 读模型重载（ideation #1）：列表查询由 SQL 判定「原文非空」，
     * 结构化证据仍在 Java 判定，与 {@link #isReady(CareerMaterial)} 同一规则。
     */
    public static boolean isReady(boolean hasSourceText, Map<String, Object> contentJson) {
        return hasSourceText || hasMeaningfulValue(contentJson);
    }

    /** 写入口（创建/更新）的证据闸门：不满足即拒绝写入，与读路径判定同一规则。 */
    public static boolean hasMeaningfulEvidence(String sourceText, Map<String, Object> contentJson) {
        return hasText(sourceText) || hasMeaningfulValue(contentJson);
    }

    private static boolean hasMeaningfulValue(Object value) {
        if (value == null) return false;
        if (value instanceof String text) return !text.isBlank();
        if (value instanceof Number) return true;
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .filter(entry -> !"title".equalsIgnoreCase(String.valueOf(entry.getKey())))
                    .anyMatch(entry -> hasMeaningfulValue(entry.getValue()));
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().anyMatch(CareerMaterialEvidence::hasMeaningfulValue);
        }
        return true;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
