package com.intelligentresume.scoring.rule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Normalizer 单元测试。
 * 覆盖：同义词归一、中文标点去除、多空格折叠。
 */
class NormalizerTest {

    private Normalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new Normalizer(Map.of(
                "spring", List.of("spring", "spring boot", "spring cloud"),
                "java", List.of("java", "jdk", "openjdk"),
                "k8s", List.of("k8s", "kubernetes"),
                "microservice", List.of("microservice", "微服务")
        ));
    }

    @Test
    @DisplayName("正常路径: 'Spring Boot' 归一化为 'spring'")
    void normalize_springBoot_canonical() {
        assertEquals("spring", normalizer.normalize("Spring Boot"));
        assertEquals("spring", normalizer.normalize("spring cloud"));
        assertEquals("spring", normalizer.normalize("SPRING"));
    }

    @Test
    @DisplayName("正常路径: 中文标点去除")
    void normalize_chinesePunctuation_removed() {
        // 中文标点被去除，中文内容保留
        String result = normalizer.normalize("微服务。");
        assertEquals("microservice", result); // "微服务" 是 "microservice" 的同义词
    }

    @Test
    @DisplayName("正常路径: 多空格折叠")
    void normalize_multipleSpaces_folded() {
        assertEquals("spring", normalizer.normalize("  Spring   Boot  "));
        assertEquals("java", normalizer.normalize("  java  "));
    }

    @Test
    @DisplayName("有序分词: 保留出现顺序（分两趟扫描会丢失顺序）")
    void tokenizeOrdered_keepsOrder() {
        assertEquals(List.of("熟悉", "Java", "与", "Spring", "Boot"),
                normalizer.tokenizeOrdered("熟悉 Java 与 Spring Boot"));
    }

    @Test
    @DisplayName("词组: 连续英文词产出词组，且不跨中文组词")
    void tokenizeWithPhrases_contiguousLatinWordsOnly() {
        List<String> tokens = normalizer.tokenizeWithPhrases(
                "精通 Spring Boot 与 Redis", Normalizer.MAX_PHRASE_WORDS);

        // 注意：本方法**保留原始大小写**，大小写折叠由调用方（ResumeKeywordExtractor.addText）负责，
        // 与既有 tokenize() 的约定一致。故断言用大小写不敏感比较。
        assertTrue(tokens.stream().anyMatch(t -> t.equalsIgnoreCase("spring boot")),
                "应产出连续词组 'Spring Boot'，实际=" + tokens);
        assertFalse(tokens.stream().anyMatch(t -> t.equalsIgnoreCase("boot redis")),
                "跨中文不应产出伪词组，实际=" + tokens);
        assertTrue(tokens.stream().anyMatch(t -> t.equalsIgnoreCase("spring")));
        assertTrue(tokens.stream().anyMatch(t -> t.equalsIgnoreCase("redis")));
    }

    @Test
    @DisplayName("词组: 受 maxWords 限制")
    void tokenizeWithPhrases_respectsMaxWords() {
        List<String> tokens = normalizer.tokenizeWithPhrases("aa bb cc dd", 2);

        assertTrue(tokens.contains("aa bb"));
        assertFalse(tokens.contains("aa bb cc"), "maxWords=2 时不应产出三词词组");
    }

    @Test
    @DisplayName("词组: 中文序列本身即完整 token，不参与组词")
    void tokenizeWithPhrases_chineseNotPhrased() {
        List<String> tokens = normalizer.tokenizeWithPhrases("熟悉 高并发 场景", Normalizer.MAX_PHRASE_WORDS);

        assertTrue(tokens.contains("熟悉"));
        assertTrue(tokens.contains("高并发"));
        assertTrue(tokens.contains("场景"));
        assertFalse(tokens.contains("熟悉 高并发"), "中文不应被空格拼接成词组");
    }

    // ---------- clean() 契约 ----------
    // 清洗规则原本在三个地方各写一份字面量，现收敛到 Normalizer.clean。
    // 以下用例钉住「收敛后行为不变」，并补齐原实现从未测过的边界（emoji / 全角标点 / 制表换行）。
    // 若有人把 clean 改回 replaceAll 手抄版，或用错字符集，这些断言会直接指出差在哪个字符类。

    @Test
    @DisplayName("clean: null / 空白 / 纯标点一律得到空串")
    void clean_degenerateInputs_empty() {
        assertEquals("", Normalizer.clean(null));
        assertEquals("", Normalizer.clean(""));
        assertEquals("", Normalizer.clean("   "));
        assertEquals("", Normalizer.clean("，。！？"));
        assertEquals("", Normalizer.clean("!!!"));
    }

    @Test
    @DisplayName("clean: 保留字母数字连字符与中文，去掉其余符号")
    void clean_keepsAlnumHyphenAndChinese() {
        assertEquals("e-commerce", Normalizer.clean("e-commerce"));
        assertEquals("nodejs", Normalizer.clean("Node.js"), "点号不在保留集，应被去掉");
        assertEquals("c", Normalizer.clean("C++"), "加号应被去掉，且大小写已折叠");
        assertEquals("微服务", Normalizer.clean("微服务。"));
        assertEquals("gcg1zgc", Normalizer.clean("GC（G1/ZGC）"), "全角括号与斜杠应被去掉");
    }

    @Test
    @DisplayName("clean: 制表 / 换行折叠为单个空格，首尾去空白")
    void clean_collapsesTabsAndNewlines() {
        assertEquals("分布式 事务", Normalizer.clean("分布式\t事务"));
        assertEquals("消息 队列 削峰", Normalizer.clean("  消息 \n 队列\t\t削峰 "));
    }

    @Test
    @DisplayName("clean: emoji 与符号被去掉（不属于字母/数字/空白/连字符）")
    void clean_dropsEmoji() {
        assertEquals("java", Normalizer.clean("Java🔥"));
        assertEquals("spring boot", Normalizer.clean("Spring 🚀 Boot"));
    }

    @Test
    @DisplayName("clean: 大小写折叠保持一致")
    void clean_lowercases() {
        assertEquals("spring boot", Normalizer.clean("SPRING BOOT"));
        assertEquals(Normalizer.clean("Spring Boot"), Normalizer.clean("spring boot"));
    }

    @Test
    @DisplayName("三处消费点共用同一清洗口径（normalize / isDirectMatch / clean）")
    void allConsumersShareSameCleaning() {
        // normalize 在空词典下退化为 clean（既是空词典契约，也是「同一口径」的直接体现）
        Normalizer emptyDict = new Normalizer(Map.of());
        String raw = "  Spring   Boot🚀 ";
        assertEquals(Normalizer.clean(raw), emptyDict.normalize(raw));

        // isDirectMatch 用 clean 后的形式去比对 token 集合
        Set<String> tokens = Set.of(Normalizer.clean(raw));
        assertTrue(normalizer.isDirectMatch("  SPRING\tBOOT ", tokens),
                "两侧各自 clean 后应能命中同一 key");
    }
}
