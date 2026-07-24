package com.intelligentresume.jobdescription.service;

import com.intelligentresume.jobdescription.dto.ParsedKeywordsResponse;
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
 *     <li>空或纯空白输入:返回 role=null, keywords=[], requirements=[]</li>
 * </ol>
 */
@Component
public class JdKeywordParser {

    private final List<String> keywordDictionary;
    private final List<String> educationKeywords;
    private final Pattern experiencePattern;

    public JdKeywordParser(
            @Value("${app.job.parser.keyword-dictionary:Java,Spring Boot,MySQL,Redis,Docker,Kubernetes}") List<String> keywordDictionary,
            @Value("${app.job.parser.education-keywords:本科,硕士,博士,Bachelor,Master,PhD}") List<String> educationKeywords,
            @Value("${app.job.parser.experience-pattern:(\\d+)\\s*年(以上)?(?:经验|工作)}") String experienceRegex) {
        this.keywordDictionary = keywordDictionary;
        this.educationKeywords = educationKeywords;
        this.experiencePattern = Pattern.compile(experienceRegex);
    }

    public ParsedKeywordsResponse parse(String jdText) {
        if (jdText == null || jdText.isBlank()) {
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
