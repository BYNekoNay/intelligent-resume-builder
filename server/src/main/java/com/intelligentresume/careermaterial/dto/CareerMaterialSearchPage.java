package com.intelligentresume.careermaterial.dto;

import com.intelligentresume.careermaterial.domain.MaterialType;

import java.util.List;
import java.util.Map;

/**
 * 搜索结果分页外壳：当页数据 + 分页元信息 + 类型计数（用于筛选标签上的徽标）。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code typeCounts} 统计的是<b>该用户全部资料</b>按类型的总数，<b>不</b>受当前
 *       type 过滤与关键词过滤影响；</li>
 *   <li>计数只包含实际有数据的类型，缺失的 key 表示 0 条，前端取数要给默认值，
 *       否则 Map 取值会拿到 null 并在页面上显示成空白；</li>
 *   <li>{@code page} 从 0 开始、{@code size} 上限 100，越界由服务层直接拒绝而不是静默修正；</li>
 *   <li>{@code totalPages} 由 Spring Data 给出，未做手工兜底。</li>
 * </ul>
 */
public record CareerMaterialSearchPage(
        List<CareerMaterialSearchItem> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        Map<MaterialType, Long> typeCounts
) {}
