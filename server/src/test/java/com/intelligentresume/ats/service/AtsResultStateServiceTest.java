package com.intelligentresume.ats.service;

import com.intelligentresume.ats.domain.AtsCheckResult;
import com.intelligentresume.ats.dto.AtsAiInsights;
import com.intelligentresume.ats.repository.AtsCheckResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AtsResultStateService} 的 taskId 比对语义（isCurrentTask 等价路径）。
 *
 * <p>聚焦一个不变量：taskId 比对在"无当前任务/无入参任务"时必须判定为不匹配，
 * 而不是抛 NPE —— {@code markFallback} 自己挡了 null，但 {@code markCompleted}
 * 的调用方约定"taskId 非空"，服务内部仍需 null 安全兜底。
 */
@ExtendWith(MockitoExtension.class)
class AtsResultStateServiceTest {

    @Mock private AtsCheckResultRepository repository;

    private AtsResultStateService service;

    @BeforeEach
    void setUp() {
        service = new AtsResultStateService(repository, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private AtsCheckResult ownedRecord(Map<String, Object> resultJson) {
        AtsCheckResult result = new AtsCheckResult();
        result.setResultJson(resultJson);
        when(repository.findOwnedForUpdate(eq(10L), eq(100L))).thenReturn(Optional.of(result));
        return result;
    }

    @Test
    @DisplayName("markCompleted: 入参 taskId 为 null 时不抛 NPE，返回 false（视为非当前任务）")
    void markCompleted_nullTaskId_returnsFalseWithoutNpe() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("aiTaskId", 7L);
        ownedRecord(json);

        AtsAiInsights insights =
                new AtsAiInsights(null, null, null, null, null, null);

        assertFalse(service.markCompleted(10L, 100L, null, insights));
    }

    @Test
    @DisplayName("markCompleted: 记录的 aiTaskId 为 null（尚无当前任务）时返回 false，不抛 NPE")
    void markCompleted_recordWithoutAiTaskId_returnsFalse() {
        Map<String, Object> json = new LinkedHashMap<>();
        ownedRecord(json);

        AtsAiInsights insights =
                new AtsAiInsights(null, null, null, null, null, null);

        assertFalse(service.markCompleted(10L, 100L, 7L, insights));
    }

    @Test
    @DisplayName("markCompleted: taskId 匹配时写入成功返回 true（对照组）")
    void markCompleted_matchingTaskId_returnsTrue() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("aiTaskId", 7L);
        AtsCheckResult result = ownedRecord(json);

        AtsAiInsights insights =
                new AtsAiInsights(null, null, null, null, null, null);

        assertTrue(service.markCompleted(10L, 100L, 7L, insights));
        verify(repository).save(result);
    }
}
