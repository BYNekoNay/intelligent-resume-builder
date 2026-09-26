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
 *
 * <p><b>空词典的静默降级契约（已知且接受，勿当缺陷上报）</b>：
 * 当 {@code synonymDictionary} 为空（未配置 {@code app.scoring.synonym-dictionary}，
 * 或配置为空 map）时，{@link Normalizer} 的同义词归一退化为<b>恒等映射</b>——
 * {@link com.intelligentresume.scoring.rule.KeywordRule} 评估结果中
 * {@code partialMatched} 恒为空列表，所有命中都记为 direct（进入 {@code matched}）。
 * 由于 {@code matched} 与 {@code partialMatched} 在分数中同权重，
 * <b>分数不受影响</b>，仅匹配明细的分类失去区分度；调用方（评分报告的消费端）
 * 因此<b>无法区分</b>「词典未配置」与「恰好全部关键词直接逐字命中」这两种情况。
 * 该降级不抛错、不打日志、不改变分数计算——这是有意为之的静默契约。
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
