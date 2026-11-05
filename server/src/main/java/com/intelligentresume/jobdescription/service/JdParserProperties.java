package com.intelligentresume.jobdescription.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * JD 解析规则配置。使用 ConfigurationProperties 绑定 YAML 数组，避免
 * {@code @Value} 将不存在的标量键回退成旧的逗号分隔默认值。
 *
 * <p>要点：
 * <ul>
 *   <li>字段默认值与 yml 中的默认值必须一致，否则"没配"和"配了空"会给出两套行为；</li>
 *   <li>列表绑定是<b>整体替换</b>：yml 里给了一个 keyword-dictionary 列表就完全覆盖
 *       这里的初始化值，不会做合并，所以配置里要给全量词典；</li>
 *   <li>{@code experiencePattern} 由 {@code JdKeywordParser} 构造时直接
 *       {@code Pattern.compile}，正则写错会在启动阶段就失败，不会拖到请求期；</li>
 *   <li>词典项参与子串匹配，大小写只在关键词匹配时被忽略，教育关键词是原样包含匹配。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "app.job.parser")
public class JdParserProperties {

    /** 关键词词典：命中即原样输出，顺序即最终输出顺序。 */
    private List<String> keywordDictionary = new ArrayList<>(List.of(
            "Java", "Spring Boot", "MySQL", "Redis", "Docker", "Kubernetes"));
    /** 教育关键词：用 contains 原样匹配，配置里的大小写就是能匹配到的大小写。 */
    private List<String> educationKeywords = new ArrayList<>(List.of(
            "本科", "硕士", "博士", "Bachelor", "Master", "PhD"));
    /** 经验年限正则：必须能整体取出"X 年…经验"，group() 会被直接当作需求项。 */
    private String experiencePattern = "(\\d+)\\s*年(以上)?(?:经验|工作)";

    public List<String> getKeywordDictionary() { return keywordDictionary; }
    public void setKeywordDictionary(List<String> keywordDictionary) { this.keywordDictionary = keywordDictionary; }
    public List<String> getEducationKeywords() { return educationKeywords; }
    public void setEducationKeywords(List<String> educationKeywords) { this.educationKeywords = educationKeywords; }
    public String getExperiencePattern() { return experiencePattern; }
    public void setExperiencePattern(String experiencePattern) { this.experiencePattern = experiencePattern; }
}
