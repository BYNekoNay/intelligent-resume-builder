package com.intelligentresume.ats.dto;

import java.util.List;

/**
 * 「解析器视角」诊断：一份简历被 ATS 读到时，机器实际看到的内容与结构。
 *
 * <p>为什么值得单独做一个端点：用户看到的是一张排版精美的 PDF / 编辑页面，
 * 而机器看到的是**线性文本 + 章节标题 + 字段块**。看不到这一层，用户就无法理解
 * 「我的简历明明写了，为什么关键词没命中」——大多数情况是：
 * 内容在，但落在解析器认不出的章节里，或字段格式解析不出来（如日期是自由文本）。
 *
 * <p>**全部由规则计算，不调 AI**：这个视角必须是可复现、可解释的，
 * 一旦掺进模型判断就不再是「解析器看到什么」，而是「模型怎么想」。
 *
 * <p>文案策略：本响应只返回**码**（{@code section} / {@code code}），
 * 由前端映射成中英文；服务端不下发中文句子，避免 en-US 界面出现中文。
 */
public record ParserViewResponse(
        Long resumeVersionId,
        int charCount,
        int lineCount,
        List<SectionView> sections,
        ContactView contact,
        List<Warning> warnings,
        String plainText
) {

    /**
     * 单个章节的解析结果。
     *
     * @param code      章节键（与前后端注册表一致，如 {@code work}）
     * @param present   解析器是否读到了内容（有至少一条非空条目）
     * @param itemCount 条目数（对象型章节为 1，集合型为条目个数）
     * @param charCount 该章节渲染出的字符数（用于判断"有但很少"）
     */
    public record SectionView(String code, boolean present, int itemCount, int charCount) {
    }

    /**
     * 联系方式识别结果。ATS 的第一道门槛就是能不能解析出联系方式，
     * 而它完全取决于字段是否落在 {@code basics} 里的标准位置。
     */
    public record ContactView(
            boolean name,
            boolean email,
            boolean emailValid,
            boolean phone,
            boolean location,
            int linkCount
    ) {
    }

    /**
     * 解析风险。
     *
     * @param code    风险码（前端映射文案），如 {@code MISSING_EMAIL} / {@code ITEM_MISSING_DATES}
     * @param section 相关章节（无章节语义时为 null）
     * @param count   受影响条目数（不适用时为 null）
     */
    public record Warning(String code, String section, Integer count) {
    }
}
