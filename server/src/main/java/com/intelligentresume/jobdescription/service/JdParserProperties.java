package com.intelligentresume.jobdescription.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * JD 解析规则配置。使用 ConfigurationProperties 绑定 YAML 数组，避免
 * {@code @Value} 将不存在的标量键回退成旧的逗号分隔默认值。
 */
@Component
@ConfigurationProperties(prefix = "app.job.parser")
public class JdParserProperties {

    private List<String> keywordDictionary = new ArrayList<>(List.of(
            "Java", "Spring Boot", "MySQL", "Redis", "Docker", "Kubernetes"));
    private List<String> educationKeywords = new ArrayList<>(List.of(
            "本科", "硕士", "博士", "Bachelor", "Master", "PhD"));
    private String experiencePattern = "(\\d+)\\s*年(以上)?(?:经验|工作)";

    public List<String> getKeywordDictionary() { return keywordDictionary; }
    public void setKeywordDictionary(List<String> keywordDictionary) { this.keywordDictionary = keywordDictionary; }
    public List<String> getEducationKeywords() { return educationKeywords; }
    public void setEducationKeywords(List<String> educationKeywords) { this.educationKeywords = educationKeywords; }
    public String getExperiencePattern() { return experiencePattern; }
    public void setExperiencePattern(String experiencePattern) { this.experiencePattern = experiencePattern; }
}
