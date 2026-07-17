package com.intelligentresume.ai.generation.validator;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt 注入检测。
 *
 * <p>骨架:T07 落地正则集与拒绝策略。
 */
@Component
public class PromptInjectionDetector {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|system|above)\\s+(instructions?|prompts?)"),
            Pattern.compile("(?i)disregard\\s+(the\\s+)?rules?"),
            Pattern.compile("(?i)you\\s+are\\s+(now|a)\\s+"),
            Pattern.compile("(?i)forget\\s+(everything|all)")
    );

    public void assertSafe(String text) {
        if (text == null) return;
        for (Pattern p : PATTERNS) {
            if (p.matcher(text).find()) {
                throw new BusinessException(ErrorCode.VALIDATION, "检测到疑似 Prompt 注入");
            }
        }
    }
}