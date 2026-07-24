package com.intelligentresume.ai.confirmation.service;

import com.intelligentresume.ai.confirmation.dto.ConfirmedDraftItem;
import com.intelligentresume.ai.confirmation.dto.ConfirmedDraftItem.Decision;
import com.intelligentresume.common.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ResumeJsonNormalizer 单元测试。
 * 覆盖：_source 剥离、_pending ACCEPT 后剥离、残留 _pending 抛错。
 */
class ResumeJsonNormalizerTest {

    private ResumeJsonNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new ResumeJsonNormalizer();
    }

    @Test
    @DisplayName("正常路径: _source 字段被剥离,业务字段保留")
    void stripSource_keepsBusinessFields() {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("basics", new LinkedHashMap<>(Map.of(
                "name", "张三",
                "_source", Map.of("materialId", 1)
        )));
        draft.put("work", List.of(
                new LinkedHashMap<>(Map.of(
                        "company", "ABC公司",
                        "role", "Java开发",
                        "_source", Map.of("materialId", 2)
                ))
        ));

        // 无 _pending，不需要 items
        Map<String, Object> result = normalizer.normalize(draft, List.of());

        // _source 被剥离
        @SuppressWarnings("unchecked")
        Map<String, Object> basics = (Map<String, Object>) result.get("basics");
        assertFalse(basics.containsKey("_source"));
        assertEquals("张三", basics.get("name"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> work = (List<Map<String, Object>>) result.get("work");
        assertFalse(work.get(0).containsKey("_source"));
        assertEquals("ABC公司", work.get(0).get("company"));
    }

    @Test
    @DisplayName("正常路径: 用户已 ACCEPT 的 _pending 也可被剥离")
    void stripPending_afterAccept() {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("work", List.of(
                new LinkedHashMap<>(Map.of(
                        "company", "ABC公司",
                        "highlights", List.of(
                                "负责后端开发",
                                new LinkedHashMap<>(Map.of(
                                        "_pending", Map.of("reason", "需补充量化成果")
                                ))
                        )
                ))
        ));

        List<ConfirmedDraftItem> items = List.of(
                new ConfirmedDraftItem("work[0].highlights[1]", Decision.ACCEPT, null)
        );

        Map<String, Object> result = normalizer.normalize(draft, items);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> work = (List<Map<String, Object>>) result.get("work");
        @SuppressWarnings("unchecked")
        List<Object> highlights = (List<Object>) work.get(0).get("highlights");
        assertEquals(2, highlights.size());
        // _pending 被剥离后变成空 Map
        @SuppressWarnings("unchecked")
        Map<String, Object> pendingItem = (Map<String, Object>) highlights.get(1);
        assertFalse(pendingItem.containsKey("_pending"));
    }

    @Test
    @DisplayName("失败路径: 残留 _pending 抛出 CONFLICT")
    void remainingPending_throws() {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("skills", List.of(
                new LinkedHashMap<>(Map.of(
                        "_pending", Map.of("reason", "需补充技能")
                ))
        ));

        // 没有对应的 ACCEPT/EDIT/REJECT 决策
        BusinessException ex = assertThrows(BusinessException.class,
                () -> normalizer.normalize(draft, List.of()));
        assertTrue(ex.getMessage().contains("_pending"));
    }
}
