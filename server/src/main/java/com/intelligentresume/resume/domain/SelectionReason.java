package com.intelligentresume.resume.domain;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;

/**
 * {@code resume_material_reference.selection_reason} 的取值域（封闭两值，D5 决策）。
 *
 * <p>为什么需要它：该字段在 {@code DraftCommitService} 里曾是
 * {@code sel.get("selectedReason").toString()} 直接透传 —— 等于把溯源字段的取值域
 * 交给了上游字符串。实测全仓只有两个写入点（AI 引用 {@code AI_REFERENCED}、
 * 用户编辑 {@code USER_EDITED}），**不存在第三个来源**，所以封闭是安全的；
 * 而未知值静默落库等于伪造溯源（查"这条引用从哪来"时给出无法解释的值）。
 *
 * <p>不做数据库 CHECK 约束：约束变更需要迁移且对历史行一律失败；
 * 由应用层常量 + {@code SelectionReasonContractTest} 门禁保证。
 */
public enum SelectionReason {

    /** AI 生成草稿时引用了该素材（{@code JobGenerationService} 写入 selected 时标注）。 */
    AI_REFERENCED,

    /** 用户在确认页编辑后产生的新素材（编辑超出原素材范围，引用指向新建的 career_material）。 */
    USER_EDITED;

    /**
     * 归一化上游字符串。
     *
     * @return {@code null}/空白 → {@code null}（该引用无原因可记，字段可空）
     * @throws BusinessException 未知取值 → {@code 40001}（拒绝而不是静默兜底：
     *         兜底会让"未知来源"以某个合法值落库，事后无法分辨）
     */
    public static SelectionReason fromClient(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim();
        for (SelectionReason reason : values()) {
            if (reason.name().equals(normalized)) {
                return reason;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION,
                "未知的选材原因: " + normalized + "（取值域: AI_REFERENCED / USER_EDITED）");
    }

    /** 落库值（与枚举名一致；集中一处，避免调用点各写各的字面量）。 */
    public String dbValue() {
        return name();
    }
}
