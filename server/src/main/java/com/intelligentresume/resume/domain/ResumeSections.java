package com.intelligentresume.resume.domain;

import java.util.List;

/**
 * 简历文档顶层章节键的唯一登记（ideation #53/#54/#55：三处 AI 上下文白名单的同一来源）。
 *
 * <p>与前端 {@code web/src/resume/sectionRegistry.ts} 的 {@code SECTION_KEYS} 对齐；
 * 新增章节时两处都要改（跨语言无法自动校验，已在两端注释互指）。
 *
 * <p>{@link #AI_CONTEXT_SECTIONS} 是允许进入 AI 输入/输出的章节：
 * <ul>
 *     <li>不含 {@code links}——它是联系方式（URL/账号）容器，生成提示词亦明确不产生链接；</li>
 *     <li>{@code basics} 在列，但各消费方必须做字段级白名单与脱敏
 *     （姓名/邮箱/电话/地址等由消费方剔除，见 CommunicationAiPromptBuilder 与
 *     InterviewContextSanitizer）。</li>
 * </ul>
 */
public final class ResumeSections {

    /** 允许进入 AI 输入/输出的章节（有序，保证提示词构建的确定性）。 */
    public static final List<String> AI_CONTEXT_SECTIONS = List.of(
            "basics", "objective", "work", "volunteering", "skills", "projects", "education",
            "courses", "certificates", "publications", "awards", "languages", "customSections");

    private ResumeSections() {
    }
}