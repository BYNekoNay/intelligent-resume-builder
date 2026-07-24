package com.intelligentresume.ai.task.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IdempotencyService 纯单元测试。
 * 覆盖:确定性指纹、键序无关、嵌套排序、长度格式。
 */
class IdempotencyServiceTest {

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService();
    }

    @Test
    @DisplayName("相同内容产生相同指纹")
    void sameContent_sameFingerprint() {
        Map<String, Object> map1 = Map.of("taskType", "JOB_GENERATION", "input", "data");
        Map<String, Object> map2 = Map.of("taskType", "JOB_GENERATION", "input", "data");

        assertEquals(service.fingerprint(map1), service.fingerprint(map2));
    }

    @Test
    @DisplayName("键插入顺序不同但内容相同 → 相同指纹（排序键规范化）")
    void differentKeyOrder_sameFingerprint() {
        Map<String, Object> map1 = new LinkedHashMap<>();
        map1.put("b", 2);
        map1.put("a", 1);

        Map<String, Object> map2 = new LinkedHashMap<>();
        map2.put("a", 1);
        map2.put("b", 2);

        assertEquals(service.fingerprint(map1), service.fingerprint(map2));
    }

    @Test
    @DisplayName("不同内容产生不同指纹")
    void differentContent_differentFingerprint() {
        Map<String, Object> map1 = Map.of("taskType", "JOB_GENERATION");
        Map<String, Object> map2 = Map.of("taskType", "RESUME_OPTIMIZE");

        assertNotEquals(service.fingerprint(map1), service.fingerprint(map2));
    }

    @Test
    @DisplayName("嵌套 Map 也按键排序")
    void nestedMap_sortedDeep() {
        Map<String, Object> inner1 = new LinkedHashMap<>();
        inner1.put("z", 1);
        inner1.put("a", 2);

        Map<String, Object> inner2 = new LinkedHashMap<>();
        inner2.put("a", 2);
        inner2.put("z", 1);

        Map<String, Object> outer1 = Map.of("nested", inner1);
        Map<String, Object> outer2 = Map.of("nested", inner2);

        assertEquals(service.fingerprint(outer1), service.fingerprint(outer2));
    }

    @Test
    @DisplayName("指纹为 32 位十六进制字符串")
    void fingerprint_is32HexChars() {
        String fp = service.fingerprint(Map.of("key", "value"));

        assertEquals(32, fp.length());
        assertTrue(fp.matches("[0-9a-f]{32}"));
    }
}
