package com.intelligentresume.ai.confirmation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 单条草稿决策。
 *
 * @param outputPath  草稿中的路径，如 "work[0].highlights[2]"
 * @param decision    ACCEPT / EDIT / REJECT
 * @param editedValue 仅 decision=EDIT 时必填，覆盖原字段
 */
public record ConfirmedDraftItem(
        @NotBlank String outputPath,
        @NotNull Decision decision,
        Map<String, Object> editedValue
) {
    /**
     * 用户对 AI 草稿条目的决策。
     */
    public enum Decision {
        /** 原样接受 */
        ACCEPT,
        /** 用 editedValue 覆盖 */
        EDIT,
        /** 从 normalized JSON 中移除 */
        REJECT
    }
}
