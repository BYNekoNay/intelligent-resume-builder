package com.intelligentresume.scoring.rule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
}
