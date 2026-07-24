package com.intelligentresume.jobdescription.service;

import com.intelligentresume.jobdescription.dto.ParsedKeywordsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JdKeywordParser 确定性规则解析测试。
 */
class JdKeywordParserTest {

    private JdKeywordParser parser;

    @BeforeEach
    void setUp() {
        parser = new JdKeywordParser(
                List.of("Java", "Spring Boot", "Spring Cloud", "MySQL", "PostgreSQL",
                        "Redis", "Kafka", "Docker", "Kubernetes", "微服务", "分布式", "高并发", "JVM", "多线程"),
                List.of("本科", "硕士", "博士", "Bachelor", "Master", "PhD"),
                "(\\d+)\\s*年(以上)?(?:经验|工作)"
        );
    }

    @Test
    @DisplayName("正常路径: 大小写不敏感命中关键词,输出词典原始大小写")
    void caseInsensitiveKeywordHit() {
        ParsedKeywordsResponse result = parser.parse("熟悉 java、SPRING BOOT 和 mysql 开发");

        assertTrue(result.keywords().contains("Java"), "应命中 Java(词典原始大小写)");
        assertTrue(result.keywords().contains("Spring Boot"));
        assertTrue(result.keywords().contains("MySQL"));
        assertEquals(3, result.keywords().size());
    }

    @Test
    @DisplayName("正常路径: 经验正则匹配 '3 年以上经验'")
    void experienceRegexHit() {
        ParsedKeywordsResponse result = parser.parse("需要 3 年以上经验,熟悉 Java 开发");

        assertTrue(result.requirements().stream().anyMatch(r -> r.contains("3年")),
                "应提取 3年经验,实际: " + result.requirements());
    }

    @Test
    @DisplayName("正常路径: 教育关键词命中 '本科及以上'")
    void educationKeywordHit() {
        ParsedKeywordsResponse result = parser.parse("学历要求本科及以上,硕士优先");

        assertTrue(result.requirements().contains("本科"));
        assertTrue(result.requirements().contains("硕士"));
    }

    @Test
    @DisplayName("边界路径: 空文本返回 role=null,keywords=[]")
    void emptyText_returnsEmpty() {
        ParsedKeywordsResponse result = parser.parse("");

        assertNull(result.role());
        assertTrue(result.keywords().isEmpty());
        assertTrue(result.requirements().isEmpty());
    }

    @Test
    @DisplayName("边界路径: 没有命中关键词的文本返回空 keywords")
    void noHit_returnsEmptyKeywords() {
        ParsedKeywordsResponse result = parser.parse("负责日常行政事务处理");

        assertTrue(result.keywords().isEmpty());
    }

    @Test
    @DisplayName("边界路径: role 取第一行非空,>60 字符截断")
    void role_truncatedOver60() {
        String longLine = "A".repeat(80);
        ParsedKeywordsResponse result = parser.parse(longLine + "\n第二行");

        assertNotNull(result.role());
        assertEquals(60, result.role().length(), "role 应截断为 60 字符");
    }

    @Test
    @DisplayName("正常路径: 重复关键词去重,保持首次出现顺序")
    void deduplicateKeepOrder() {
        ParsedKeywordsResponse result = parser.parse("Java 开发,熟悉 Java 和 MySQL,Java 优先");

        assertEquals(2, result.keywords().size(), "Java 应只出现一次");
        assertEquals("Java", result.keywords().get(0));
        assertEquals("MySQL", result.keywords().get(1));
    }
}
