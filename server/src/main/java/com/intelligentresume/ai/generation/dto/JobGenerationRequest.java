package com.intelligentresume.ai.generation.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 岗位定制生成请求。通过 CreateAiTaskRequest.input 传递,
 * 本 record 用于 JobGenerationService 内部解析。
 *
 * <p>一句人话：用户发起"按这个岗位生成简历"时勾的三类资料——必须用（included）、
 * 优先用（preferred）、不要用（excluded），加上目标简历和岗位两个 ID。
 *
 * <p>要点：
 * <ul>
 *   <li>三个 ID 列表都可为 null —— 只有 {@code targetResumeId} / {@code jobDescriptionId} 是必需的。</li>
 *   <li>本 record 只用于内部解析（{@code MaterialSelector} 的入参），不经 HTTP 直接反序列化，
 *       因此 {@code @NotNull} 在这里不会触发 Bean Validation。</li>
 *   <li>三个列表的语义有优先级：excluded &gt; included &gt; preferred，具体规则见
 *       {@code MaterialSelector}。</li>
 * </ul>
 */
public record JobGenerationRequest(
        @NotNull Long targetResumeId,
        @NotNull Long jobDescriptionId,
        List<Long> includedMaterialIds,
        List<Long> preferredMaterialIds,
        List<Long> excludedMaterialIds
) {
}
