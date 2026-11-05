package com.intelligentresume.communication.service;

import com.intelligentresume.communication.domain.CommunicationType;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 模型输出的结构与语言闸门：不合格直接判失败，不做任何"清洗后放行"。
 *
 * <p>红线：
 * <ul>
 *   <li><b>字段集合必须精确等于 {@code {subject, body}}</b>：多字段、少字段都拒，防止模型自造字段被下游忽略后静默错位。</li>
 *   <li><b>subject 与 type 强绑定</b>：EMAIL 必须有 subject，其它类型必须为 null。</li>
 *   <li><b>语言判定是启发式</b>：中文要求「出现汉字」或「拉丁词少于 6 个」；英文只要出现汉字就拒。
 *       纯数字/短英文夹杂在中文文案里不会被误判，但大段未翻译英文会被拦。</li>
 *   <li>所有异常都是不可重试的 {@code CommunicationAiException}；调用方据此只做一次修复重问。</li>
 * </ul>
 */
@Component
public class CommunicationAiResultValidator {
    private static final Set<String> KEYS = Set.of("subject", "body");
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}]");
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z]{2,}");

    public ValidatedResult validate(Map<String, Object> output, CommunicationType type, String outputLanguage) {
        if (output == null || !output.keySet().equals(KEYS)) {
            throw invalid("Unexpected communication AI response fields");
        }
        String body = requiredText(output.get("body"), "body", 20, 4_000);
        String subject = optionalText(output.get("subject"), "subject", 200);
        if (type == CommunicationType.EMAIL && subject == null) {
            throw invalid("subject is required for EMAIL");
        }
        if (type != CommunicationType.EMAIL && subject != null) {
            throw invalid("subject must be null unless type is EMAIL");
        }
        validateLanguage((subject == null ? "" : subject + " ") + body, outputLanguage);
        return new ValidatedResult(subject, body);
    }

    private void validateLanguage(String text, String outputLanguage) {
        boolean chinese = CJK.matcher(text).find();
        int latinWords = 0;
        var matcher = LATIN_WORD.matcher(text);
        while (matcher.find()) latinWords++;
        if ("ZH_CN".equals(outputLanguage) && !chinese && latinWords >= 6) {
            throw invalid("Narrative output must be Simplified Chinese");
        }
        if ("EN".equals(outputLanguage) && chinese) {
            throw invalid("Narrative output must be English");
        }
    }

    private String requiredText(Object value, String field, int min, int max) {
        if (!(value instanceof String text) || text.isBlank()) throw invalid(field + " must be a non-blank string");
        String normalized = text.trim();
        if (normalized.length() < min || normalized.length() > max) {
            throw invalid(field + " length is outside the allowed range");
        }
        return normalized;
    }

    private String optionalText(Object value, String field, int max) {
        if (value == null || value instanceof String text && text.isBlank()) return null;
        if (!(value instanceof String text)) throw invalid(field + " must be a string or null");
        String normalized = text.trim();
        if (normalized.length() > max) throw invalid(field + " is too long");
        return normalized;
    }

    private CommunicationAiException invalid(String message) {
        return new CommunicationAiException(message, false);
    }

    public record ValidatedResult(String subject, String body) {
    }
}
