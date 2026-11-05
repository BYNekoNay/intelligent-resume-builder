package com.intelligentresume.jobdescription.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建 JD 的请求体。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code title} 与 {@code jdText} 必填非空白，{@code companyName} 可空；</li>
 *   <li>这里的 {@code @Size} 只管字段长度，正文真正的上限是
 *       {@code app.job.jd-text.max-length}（默认 5000），由 Service 校验，两者口径不同；</li>
 *   <li>创建时不解析关键词，解析是独立的 POST /{id}/parse 动作。</li>
 * </ul>
 */
public record CreateJobDescriptionRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 255) String companyName,
        @NotBlank String jdText
) {}
