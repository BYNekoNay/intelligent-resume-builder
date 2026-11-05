package com.intelligentresume.resume.domain;

/**
 * 简历版本来源类型：标记"这条版本是怎么产生的"，纯审计用途，不参与权限或状态流转判断。
 *
 * <p>要点：
 * <ul>
 *     <li>以 {@code EnumType.STRING} 落库（列长 32），枚举名即数据库值，改名等于改数据契约。</li>
 *     <li>当前主代码里实际写入的只有三个：{@code MANUAL}（手动创建）、{@code RESTORED}（恢复历史版本）、
 *         {@code JD_CUSTOMIZED}（草稿确认落库）。</li>
 *     <li>{@code AI_OPTIMIZED} / {@code MATERIAL_CUSTOMIZED} 在 server 侧没有写入点，
 *         读到时只可能来自历史数据或未来新增链路。</li>
 * </ul>
 */
public enum ResumeSourceType {
    MANUAL,
    AI_OPTIMIZED,
    JD_CUSTOMIZED,
    MATERIAL_CUSTOMIZED,
    RESTORED
}
