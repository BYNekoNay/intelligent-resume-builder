package com.intelligentresume.ats.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 关键词缺口（B4）：把「命中/仅同义词命中/完全缺失」三档分开呈现。
 *
 * <p>此前这份信息只以一句「缺少 JD 关键词: a, b」写进 {@code risks}，
 * 用户无法判断优先级，也看不出同义词命中（语义上有、字面上没有）这类中间状态。
 *
 * <p>{@code null} 表示该检查记录产生于本字段引入之前 —— 前端据此隐藏面板，
 * 不要用空列表冒充「检查过且全部命中」。
 */
public record KeywordGap(
        List<String> matched,
        List<String> partialMatched,
        List<String> missing,
        BigDecimal coverage
) {}
