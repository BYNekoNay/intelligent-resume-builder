package com.intelligentresume.ai.confirmation.service;

import com.intelligentresume.ai.confirmation.dto.ConfirmedDraftItem;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.domain.SelectionReason;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确认草稿时「选材溯源」的两项前置校验（ADR-013）。
 *
 * <p>从 {@code DraftCommitService} 抽出，理由是两件事都属于**写库之前的准入检查**，
 * 而 service 已到行数棘轮上限（{@code source-size-baseline.txt}，只允许下调）：
 * 把它们混在提交主流程里既让主流程难读，也逼着后续每次改动都去动一个超限文件。
 *
 * <p>职责边界：
 * <ul>
 *   <li>{@link #decisionsByPath} / {@link #decisionForSourcePath}：路径覆盖判定（支持父路径前缀）；</li>
 *   <li>{@link #validateAndNormalize}：①selected 里的每一处溯源都必须被 items 覆盖（否则 40904）；
 *       ②{@code selection_reason} 归一化，未知值抛 40001。</li>
 * </ul>
 * 本类**不做任何 IO**：不读库、不写库、不调 provider。
 */
final class SelectionProvenance {

    private SelectionProvenance() {
    }

    /** items → 按 outputPath 建索引（后出现的覆盖先出现的，与主流程一致）。 */
    static Map<String, ConfirmedDraftItem> decisionsByPath(List<ConfirmedDraftItem> items) {
        Map<String, ConfirmedDraftItem> byPath = new LinkedHashMap<>();
        for (ConfirmedDraftItem item : items) {
            byPath.put(item.outputPath(), item);
        }
        return byPath;
    }

    /**
     * 找覆盖该素材路径的用户决策：完全相同，或决策路径是它的祖先（`work[0]` 覆盖 `work[0].x`）。
     * 多个候选时取**最长**的决策路径（最具体的那条）。
     */
    static ConfirmedDraftItem decisionForSourcePath(String sourcePath, Map<String, ConfirmedDraftItem> decisionsByPath) {
        ConfirmedDraftItem match = null;
        int longestMatch = -1;
        for (ConfirmedDraftItem item : decisionsByPath.values()) {
            String decisionPath = item.outputPath();
            if ((sourcePath.equals(decisionPath)
                    || sourcePath.startsWith(decisionPath + ".")
                    || sourcePath.startsWith(decisionPath + "["))
                    && decisionPath.length() > longestMatch) {
                match = item;
                longestMatch = decisionPath.length();
            }
        }
        return match;
    }

    /**
     * 校验 selected 的覆盖率并归一化选材原因。
     *
     * <p>返回值为 {@code outputPath + "#" + materialId} → 归一化后的原因，供落库时直接取用
     * （避免在主流程里再解析一遍字符串）。
     *
     * @throws BusinessException 路径未被 items 覆盖 → {@code 40904}；原因取值未知 → {@code 40001}
     */
    static Map<String, SelectionReason> validateAndNormalize(List<Map<String, Object>> selected,
                                                             List<ConfirmedDraftItem> items) {
        Map<String, SelectionReason> reasonByPath = new LinkedHashMap<>();
        if (selected == null) {
            return reasonByPath;
        }
        Map<String, ConfirmedDraftItem> decisions = decisionsByPath(items);
        for (Map<String, Object> sel : selected) {
            Long materialId = toLong(sel.get("materialId"));
            if (materialId == null) {
                continue;  // 无素材 ID 的条目本来就不产生引用
            }
            String sourcePath = sel.get("outputPath") != null ? sel.get("outputPath").toString() : "";
            if (decisionForSourcePath(sourcePath, decisions) == null) {
                throw new BusinessException(ErrorCode.SELECTION_ITEM_UNKNOWN,
                        "选材已过期：素材路径 " + sourcePath + "（材料 ID " + materialId
                                + "）未被本次确认覆盖，请重新载入后确认");
            }
            SelectionReason reason = SelectionReason.fromClient(
                    sel.get("selectedReason") != null ? sel.get("selectedReason").toString() : null);
            reasonByPath.put(sourcePath + "#" + materialId, reason);
        }
        return reasonByPath;
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
