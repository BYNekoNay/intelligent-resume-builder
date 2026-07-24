package com.intelligentresume.ai.generation.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt 注入检测器。基于正则匹配用户输入(JD 原文等),
 * 命中时在 warnings 中追加标记,但不阻止任务执行。
 */
@Component
public class PromptInjectionDetector {

    private final List<Pattern> patterns;

    public PromptInjectionDetector(
            @Value("${app.ai.generation.injection-detection.suspicious-patterns:}") List<String> regexPatterns) {
        this.patterns = regexPatterns.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(Pattern::compile)
                .toList();
    }

    public DetectionResult detect(String jdText, List<String> userInputs) {
        List<String> matched = new ArrayList<>();
        List<String> allInputs = new ArrayList<>();
        if (jdText != null) {
            allInputs.add(jdText);
        }
        if (userInputs != null) {
            allInputs.addAll(userInputs);
        }

        for (String input : allInputs) {
            for (Pattern pattern : patterns) {
                if (pattern.matcher(input).find()) {
                    String p = pattern.pattern();
                    if (!matched.contains(p)) {
                        matched.add(p);
                    }
                }
            }
        }
        return new DetectionResult(!matched.isEmpty(), matched);
    }

    public record DetectionResult(boolean suspicious, List<String> matchedPatterns) {
    }
}
