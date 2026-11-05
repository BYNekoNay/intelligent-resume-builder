package com.intelligentresume.imports.dto;
import java.util.Map;

/**
 * 导入解析结果：原文 + 归一化建议字段。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code extractedText} 是清洗后的纯文本（去掉 NUL、首尾裁剪），后续建资料/写简历
 *       应以它为准；{@code normalizedResumeInput} 只是在同一份文本上做的正则启发式建议；</li>
 *   <li>{@code normalizedResumeInput} 当前固定为 {@code {"basics": {name, email?, phone?, summary}}}，
 *       email/phone 抽不到时<b>缺键</b>而不是空串，summary 是全文而非摘要；</li>
 *   <li>{@code originalFileStored} 目前恒为 false：原文件不落盘，客户端不应据此提供"下载原文件"入口；</li>
 *   <li>{@code fileName} 仅回显用户上传名，未做路径拼接，也不保证唯一。</li>
 * </ul>
 */
public record ResumeImportResponse(String fileName, String mediaType, String extractedText, Map<String, Object> normalizedResumeInput, boolean originalFileStored) {}
