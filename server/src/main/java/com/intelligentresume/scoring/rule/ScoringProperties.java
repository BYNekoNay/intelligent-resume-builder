package com.intelligentresume.scoring.rule;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评分规则配置（{@code app.scoring}）。
 *
 * <p><b>为什么需要这个类</b>：原先 {@link Normalizer} 用
 * {@code @Value("#{${app.scoring.synonym-dictionary:{}}}")} 注入同义词词典。
 * 该写法只对「properties 单行 Map 字面量」有效；而本项目在 YAML 中写成**嵌套 map**，
 * Spring 会把它展平成索引键（{@code app.scoring.synonym-dictionary.java[0]}），
 * 并不存在名为 {@code app.scoring.synonym-dictionary} 的单个键 ——
 * 于是占位符走了默认值 {@code {}}，SpEL 求值得到空 Map。
 *
 * <p>后果是**词典在整个运行时始终为空**，而配置看上去完全正常（scalar 属性如
 * {@code app.scoring.rule-version} 用 {@code @Value("${...}")} 是能正常读取的，
 * 只有 Map 这条 SpEL 路径失效）。表现为：JD 关键词中的**含空格多词短语**
 * （如 {@code Spring Boot}）即使与简历**逐字相同**也必然被判缺失，
 * keywordCoverage 被系统性低估。
 *
 * <p>改用 {@code @ConfigurationProperties} 后嵌套 map 能正确绑定 —— 与本项目
 * {@link com.intelligentresume.jobdescription.service.JdParserProperties} 的既有写法一致。
 *
 * <p>回归测试：{@code ScoringDictionaryBindingIT}（断言词典非空且分词结果正确）。
 * 历史背景见 {@code docs/reviews/2026-09-25-cloud-functional-test.md} §6。
 */
@Component
@ConfigurationProperties(prefix = "app.scoring")
public class ScoringProperties {

    /** canonical → 同义词列表（含自身）。 */
    private Map<String, List<String>> synonymDictionary = new LinkedHashMap<>();

    public Map<String, List<String>> getSynonymDictionary() {
        return synonymDictionary;
    }

    public void setSynonymDictionary(Map<String, List<String>> synonymDictionary) {
        this.synonymDictionary = synonymDictionary != null ? synonymDictionary : new LinkedHashMap<>();
    }
}
