package com.intelligentresume.ai.generation.dto;

/**
 * 缺失项:草稿中缺少某板块及原因。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code section} 用的是草稿顶层键（{@code work} / {@code education} …），不是界面标题。</li>
 *   <li>由后端根据草稿内容推导，不是模型自报；但 {@code reason} 可能是模型写的
 *       {@code _pending.reason} 原文，因此不要假设它是固定枚举。</li>
 *   <li>当前只检 {@code basics} / {@code work} / {@code education} / {@code skills} 四个板块，
 *       其余板块缺失不会出现在这里。</li>
 * </ul>
 */
public record MissingItem(String section, String reason) {
}
