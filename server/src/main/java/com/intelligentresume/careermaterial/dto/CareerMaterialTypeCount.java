package com.intelligentresume.careermaterial.dto;

import com.intelligentresume.careermaterial.domain.MaterialType;

/**
 * 按资料类型聚合的行数（{@code group by material_type} 的投影）。
 *
 * <p>要点：
 * <ul>
 *   <li>只承载"有数据的类型"，没有任何资料的枚举值不会出现在结果里；</li>
 *   <li>{@code count} 是当前用户的条数，查询已在 SQL 内按 userId 收口，
 *       复用本类型时不要另写不带 userId 的聚合；</li>
 *   <li>被软删的行因 {@code @SQLRestriction} 已排除，计数与列表口径一致。</li>
 * </ul>
 */
public record CareerMaterialTypeCount(MaterialType materialType, long count) {}
