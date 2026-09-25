package com.intelligentresume.scoring.rule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归测试：{@code app.scoring.synonym-dictionary} 必须**真正从 YAML 绑定进来**。
 *
 * <p><b>为什么必须有这条测试</b>：该配置原先用
 * {@code @Value("#{${app.scoring.synonym-dictionary:{}}}")} 注入，对嵌套 YAML 无效，
 * 导致词典在**整个运行时恒为空**；而既有的 {@code NormalizerTest} / {@code KeywordRuleTest}
 * 都是手工 {@code new Normalizer(Map.of(...))} 注入词典，**完全绕过了配置绑定**，
 * 因此这个缺陷长期存在且未被任何测试发现。
 *
 * <p>它的实际后果：JD 关键词里的多词短语（如 {@code Spring Boot}）与简历逐字相同也判缺失，
 * keywordCoverage 被系统性低估。详见
 * {@code docs/reviews/2026-09-25-cloud-functional-test.md} §6。
 *
 * <p>本测试走**真实 Spring 上下文**（profile=test，词典由
 * {@code src/test/resources/application-test.yml} 提供），因此能真正拦截"注入方式退化"。
 */
@SpringBootTest
@ActiveProfiles("test")
class ScoringDictionaryBindingIT {

    @Autowired
    private Normalizer normalizer;

    @Autowired
    private ScoringProperties scoringProperties;

    @Test
    @DisplayName("配置绑定: 同义词词典非空（为空即复现原缺陷）")
    void synonymDictionaryIsBound() {
        assertFalse(scoringProperties.getSynonymDictionary().isEmpty(),
                "app.scoring.synonym-dictionary 未绑定。若此断言失败，说明配置注入方式又退化回"
                        + "对嵌套 YAML 无效的写法（@Value + SpEL map 字面量），词典会静默为空。");
    }

    @Test
    @DisplayName("配置绑定: 'Spring Boot' 归一化为 canonical 'spring'")
    void springBootNormalizesToCanonical() {
        assertEquals("spring", normalizer.normalize("Spring Boot"));
        assertEquals("spring", normalizer.normalize("spring cloud"));
        assertEquals("java", normalizer.normalize("OpenJDK"));
        assertEquals("k8s", normalizer.normalize("kubernetes"));
    }
}
