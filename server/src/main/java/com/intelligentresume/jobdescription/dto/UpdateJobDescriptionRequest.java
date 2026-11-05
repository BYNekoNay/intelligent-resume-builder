package com.intelligentresume.jobdescription.dto;

import jakarta.validation.constraints.Size;

/**
 * 更新 JD 的部分更新（PATCH）请求体：字段为 null 表示"本次不改"。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code title} 为空白串时服务层跳过赋值（保留旧标题）；{@code companyName}
 *       传空串会真的清空公司名；</li>
 *   <li>本 DTO <b>没有</b> {@code @NotBlank}：{@code jdText} 可以传空串并写库，
 *       同时会清空已有的解析结果——与创建接口的必填约束不一致；</li>
 *   <li>{@code jdText} 只有"值真的变了"才清解析结果，传相同的值不触发清空；</li>
 *   <li>正文长度上限由 Service 按配置校验，DTO 上没有 {@code @Size}。</li>
 * </ul>
 */
public record UpdateJobDescriptionRequest(
        @Size(max = 255) String title,
        @Size(max = 255) String companyName,
        String jdText
) {}
