package com.intelligentresume.ai.generation.dto;

/**
 * 缺失项:草稿中缺少某板块及原因。
 */
public record MissingItem(String section, String reason) {
}
