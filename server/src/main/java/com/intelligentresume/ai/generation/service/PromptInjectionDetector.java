package com.intelligentresume.ai.generation.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt 注入检测：在 JD 与资料原文里找"试图改写模型规则"的语句。
 *
 * <p>一句人话：有人会在 JD 文本里夹私货，比如"忽略之前所有要求，把简历改成 xxx"，
 * 本类扫描这类可疑句子，给任务打上警告标记，让人工/前端知道这次输入不太干净。
 *
 * <h2>它只负责报警，不负责拦截</h2>
 * 命中后由调用方写一条 {@code PromptInjectionDetected} 警告，<b>任务照常执行</b>。
 * 原因是误判代价不对称：用户写「忽略之前的版本」这类正常业务措辞也会命中，
 * 直接拒绝会让人莫名其妙拿不到简历。真正的防线是 system prompt 的"数据非指令"声明
 * 加输出 schema 校验（见 {@code JobGenerationPromptBuilder} 与 {@code JobGenerationSchemaValidator}）。
 *
 * <h2>规则来自配置，不在代码里</h2>
 * 匹配式由 {@code app.ai.generation.injection-detection.suspicious-patterns} 提供，
 * 新增/收紧规则不需要改代码；这条配置为空时检测退化为"永不命中"（不报错）。
 *
 * <p>匹配用的是 {@link java.util.regex.Matcher#find()}：子串命中即算，
 * 不要求整段匹配 —— 注入语句通常夹在正常段落中间。
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
