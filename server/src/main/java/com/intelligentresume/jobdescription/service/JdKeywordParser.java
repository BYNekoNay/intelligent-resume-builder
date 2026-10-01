package com.intelligentresume.jobdescription.service;

import com.intelligentresume.jobdescription.dto.ParsedKeywordsResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性 JD 关键词解析器。严禁调用任何 LLM。
 *
 * <p>规则:
 * <ol>
 *     <li>关键词:在词典中大小写不敏感匹配,保留词典原始大小写,去重保持首次出现顺序</li>
 *     <li>经验:正则匹配 "X 年(以上)?(经验|工作)"</li>
 *     <li>教育:教育关键词词典命中</li>
 *     <li>role:取 jdText 第一行非空内容,>60 字符截断</li>
 *     <li>空或纯空白输入、或长度短于 {@code app.job.jd-text.min-length}:返回 role=null, keywords=[], requirements=[]</li>
 * </ol>
 */
@Component
public class JdKeywordParser {

    /** 与 `app.job.jd-text.min-length` 的 yml 默认值保持一致。 */
    static final int DEFAULT_MIN_LENGTH = 20;

    private final List<String> keywordDictionary;
    private final List<String> educationKeywords;
    private final Pattern experiencePattern;
    private final int minLength;

    @Autowired
    public JdKeywordParser(JdParserProperties properties,
                           @Value("${app.job.jd-text.min-length:20}") int minLength) {
        this(properties.getKeywordDictionary(), properties.getEducationKeywords(),
                properties.getExperiencePattern(), minLength);
    }

    /** Constructor kept explicit for deterministic unit tests. */
    public JdKeywordParser(List<String> keywordDictionary, List<String> educationKeywords, String experienceRegex) {
        this(keywordDictionary, educationKeywords, experienceRegex, DEFAULT_MIN_LENGTH);
    }

    public JdKeywordParser(List<String> keywordDictionary, List<String> educationKeywords,
                           String experienceRegex, int minLength) {
        this.keywordDictionary = keywordDictionary;
        this.educationKeywords = educationKeywords;
        this.experiencePattern = Pattern.compile(experienceRegex);
        this.minLength = minLength;
    }

    public ParsedKeywordsResponse parse(String jdText) {
        if (jdText == null || jdText.isBlank()) {
            return new ParsedKeywordsResponse(null, List.of(), List.of());
        }
        // T05 口径（2026-10-01 决策 D3 实施）：短于 `app.job.jd-text.min-length` 的文本视为**无有效内容** ——
        // 允许解析，但 role/keywords/requirements 全空；**不**拒绝入参（「招 Java 工程师」这类短 JD 是合法输入，
        // 只是信息量不足以抽取关键词）。此前该配置声明却无消费点，改它不生效。
        if (jdText.trim().length() < minLength) {
            return new ParsedKeywordsResponse(null, List.of(), List.of());
        }

        String role = extractRole(jdText);
        List<String> keywords = extractKeywords(jdText);
        List<String> requirements = extractRequirements(jdText);

        return new ParsedKeywordsResponse(role, keywords, requirements);
    }

    private String extractRole(String jdText) {
        String[] lines = jdText.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed.length() > 60 ? trimmed.substring(0, 60) : trimmed;
            }
        }
        return null;
    }

    private List<String> extractKeywords(String jdText) {
        String lower = jdText.toLowerCase();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String keyword : keywordDictionary) {
            if (lower.contains(keyword.toLowerCase())) {
                result.add(keyword);
            }
        }
        return new ArrayList<>(result);
    }

    private List<String> extractRequirements(String jdText) {
        LinkedHashSet<String> result = new LinkedHashSet<>();

        // 经验年限
        Matcher matcher = experiencePattern.matcher(jdText);
        while (matcher.find()) {
            result.add(matcher.group().replaceAll("\\s+", ""));
        }

        // 教育关键词
        for (String edu : educationKeywords) {
            if (jdText.contains(edu)) {
                result.add(edu);
            }
        }

        return new ArrayList<>(result);
    }
}
