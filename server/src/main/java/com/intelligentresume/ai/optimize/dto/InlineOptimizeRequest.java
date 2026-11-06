package com.intelligentresume.ai.optimize.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 单段文本润色请求（/api/ai/inline-optimize）：用户在编辑器里选中一段内容，要求就地改写。
 *
 * <p>一句人话："优化这段话"请求的载荷——哪份简历、哪一节、选中的原文、
 * 可选的 JD 背景上下文。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code resumeVersionId} / {@code section} / {@code content} 必填；{@code content} 与
 *       {@code jdContext} 各有 30000 字符上限 —— 超长直接 400，不会进模型。</li>
 *   <li>{@code jobDescriptionId} 可选；给了就额外做一次归属校验（不存在或不属于当前用户一律 404），
 *       没给则 {@code jdContext} 由客户端自带。</li>
 *   <li>这三个字段（版本 ID / 章节 / 正文）会被原样塞进任务 input 交给 worker，
 *       本 DTO 不做任何裁剪或拼装。</li>
 *   <li>本类用可变 POJO 而非 record，是为了配合字段级注解与 Jackson 绑定；
 *       另有一个只带 4 个参数的构造器，它<b>不会</b>设置 {@code jobDescriptionId}。</li>
 * </ul>
 */
public class InlineOptimizeRequest {

    @NotNull(message = "resumeVersionId is required")
    @Positive(message = "resumeVersionId must be positive")
    private Long resumeVersionId;

    @NotBlank(message = "section is required")
    @Size(max = 64, message = "section must not exceed 64 characters")
    private String section;

    @NotBlank(message = "content is required")
    @Size(max = 30000, message = "content must not exceed 30000 characters")
    private String content;

    @Size(max = 30000, message = "jdContext must not exceed 30000 characters")
    private String jdContext;

    @Positive(message = "jobDescriptionId must be positive")
    private Long jobDescriptionId;

    public InlineOptimizeRequest() {}

    public InlineOptimizeRequest(Long resumeVersionId, String section, String content, String jdContext) {
        this.resumeVersionId = resumeVersionId;
        this.section = section;
        this.content = content;
        this.jdContext = jdContext;
    }

    public Long getResumeVersionId() { return resumeVersionId; }
    public void setResumeVersionId(Long resumeVersionId) { this.resumeVersionId = resumeVersionId; }
    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getJdContext() { return jdContext; }
    public void setJdContext(String jdContext) { this.jdContext = jdContext; }
    public Long getJobDescriptionId() { return jobDescriptionId; }
    public void setJobDescriptionId(Long jobDescriptionId) { this.jobDescriptionId = jobDescriptionId; }
}
