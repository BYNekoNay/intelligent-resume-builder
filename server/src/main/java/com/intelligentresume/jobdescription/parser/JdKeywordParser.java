package com.intelligentresume.jobdescription.parser;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

/**
 * 确定性 JD 关键词解析器(不调用 LLM)。
 *
 * <p>输出结构:
 * <ul>
 *     <li>{@code keywords}:从技术栈词典匹配得到的关键词(去重、保持原大小写)</li>
 *     <li>{@code skills}:词典中与"技能"相关的子集</li>
 *     <li>{@code requirements}:从正则提取的年限、教育等硬性要求(纯文本短句)</li>
 *     <li>{@code experienceYears}:从「3 年以上」「5+ years」之类提取的数字;取最大值</li>
 * </ul>
 */
@Component
public class JdKeywordParser {

    public static final String PARSER_VERSION = "v1.0.0";

    /** 技术栈词典。匹配时不区分大小写,保留首次匹配的原大小写。 */
    private static final List<String> TECH_DICTIONARY = List.of(
            "Java", "Spring", "Spring Boot", "Spring Security", "JPA", "Hibernate",
            "MySQL", "PostgreSQL", "Redis", "MongoDB", "Elasticsearch", "Kafka",
            "RabbitMQ", "Docker", "Kubernetes", "AWS", "GCP", "Azure",
            "TypeScript", "JavaScript", "Node.js", "Vue", "React", "Next.js",
            "Python", "Django", "Flask", "Go", "Golang", "Rust", "C++", "C#",
            "GraphQL", "REST", "gRPC", "Microservices",
            "JUnit", "Jest", "Pytest", "Selenium", "Cypress", "Playwright",
            "Git", "CI/CD", "Jenkins", "GitLab", "GitHub Actions",
            "Linux", "Nginx", "Tomcat",
            "Flyway", "Liquibase", "MyBatis"
    );

    private static final List<String> SKILL_HINTS = List.of(
            "Spring Boot", "Spring Security", "JPA", "Hibernate", "MySQL",
            "Redis", "Kafka", "TypeScript", "Vue", "React", "Docker", "Kubernetes",
            "GraphQL", "Microservices", "CI/CD"
    );

    private static final Pattern YEARS_PATTERN = Pattern.compile(
            "(?:(\\d{1,2})\\s*(?:\\+)?\\s*(?:years?|yrs?|年))|(?:(\\d{1,2})\\s*\\+\\s*(?:years?|yrs?))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EDUCATION_PATTERN = Pattern.compile(
            "(本科|硕士|博士|大专|bachelor|master|phd|PhD|MBA)", Pattern.CASE_INSENSITIVE);

    private static final Pattern RESPONSIBILITY_PATTERN = Pattern.compile(
            "(?:负责|参与|主导|协助|build|design|develop|maintain|负责|lead|own|implement)[^。\\n]{2,80}",
            Pattern.CASE_INSENSITIVE);

    public Map<String, Object> parse(String jdText) {
        if (jdText == null) jdText = "";
        String norm = jdText.toLowerCase(Locale.ROOT);

        Set<String> matchedTech = new LinkedHashSet<>();
        for (String tech : TECH_DICTIONARY) {
            if (norm.contains(tech.toLowerCase(Locale.ROOT))) {
                matchedTech.add(tech);
            }
        }
        List<String> keywords = new ArrayList<>(matchedTech);

        List<String> skills = new ArrayList<>();
        for (String s : SKILL_HINTS) {
            if (matchedTech.contains(s)) skills.add(s);
        }

        List<String> requirements = new ArrayList<>();
        REQUIREMENT_LABEL:
        {
            int years = extractYears(norm);
            if (years > 0) requirements.add(years + " 年以上相关经验");
            Matcher edu = EDUCATION_PATTERN.matcher(jdText);
            while (edu.find()) {
                requirements.add("学历:" + edu.group());
            }
            Matcher duty = RESPONSIBILITY_PATTERN.matcher(jdText);
            while (duty.find() && requirements.size() < 8) {
                String g = duty.group().trim();
                if (!requirements.contains(g)) requirements.add(g);
            }
        }

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("keywords", keywords);
        data.put("skills", skills);
        data.put("requirements", requirements);
        data.put("experienceYears", maxYears(norm));

        return Map.of(
                "version", PARSER_VERSION,
                "data", data
        );
    }

    private int maxYears(String norm) {
        Matcher m = YEARS_PATTERN.matcher(norm);
        int max = 0;
        while (m.find()) {
            String s = m.group(1);
            if (s == null) s = m.group(2);
            try {
                int v = Integer.parseInt(s);
                if (v > max && v < 60) max = v;
            } catch (NumberFormatException ignored) {}
        }
        return max;
    }

    private int extractYears(String norm) {
        return maxYears(norm);
    }
}
