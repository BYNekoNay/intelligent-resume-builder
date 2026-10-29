package com.intelligentresume.ai.task.repository;

import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.domain.ConfirmationStatus;
import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分组聚合查询的正确性（2026-10-29 消除 scrape 放大）。
 *
 * <p>为什么必须用真实库验证：JPQL 的 {@code GROUP BY} + {@code COALESCE(SUM(CASE ...))}
 * 与单类型 {@code count} 是**两套实现算同一个口径**，若口径漂移（例如漏了「重试计次」），
 * 表现是 gauge 读数与真实消耗量不符 —— 而配额告警只看 gauge，会从此建立在一个错误的基数上。
 * 本测试对同一批数据同时跑两条路径并逐类型对比。
 */
@SpringBootTest
@ActiveProfiles("test")
class AiTaskQuotaAggregationIT {

    @Autowired private AiTaskRepository taskRepository;
    @Autowired private UserRepository userRepository;

    /** 真实 user 行：ai_task.user_id 有外键约束，凭空造 id 会直接失败。 */
    private static Long userId;

    private static final LocalDateTime TODAY_START = LocalDate.now().atStartOfDay();

    @Test
    @DisplayName("分组查询与单类型查询逐类型相等（含重试计次口径与超过起始时间的过滤）")
    void groupedQueryMatchesPerTypeQuery() {
        User user = new User();
        user.setUsername("agg_it_user");
        user.setEmail("agg-it@example.test");
        user.setPasswordHash("$2a$10$abcdefghijklmnopqrstuv");
        userId = userRepository.findByUsername("agg_it_user")
                .orElseGet(() -> userRepository.save(user)).getId();

        // 三种类型、覆盖 retryCount = 0 / 1 / 2 / 3 与「昨天创建」的边界样本
        save(AiTaskType.JOB_GENERATION, 0, TODAY_START.plusHours(1));
        save(AiTaskType.JOB_GENERATION, 1, TODAY_START.plusHours(2));
        save(AiTaskType.JOB_GENERATION, 2, TODAY_START.plusHours(3));
        save(AiTaskType.ATS_ANALYSIS, 0, TODAY_START.plusHours(4));
        save(AiTaskType.ATS_ANALYSIS, 3, TODAY_START.plusHours(5));
        // 昨天的任务不得计入今日
        save(AiTaskType.JOB_GENERATION, 0, TODAY_START.minusMinutes(1));

        Map<AiTaskType, Long> grouped = new HashMap<>();
        for (Object[] row : taskRepository.sumAttemptsGroupedByTaskType(TODAY_START)) {
            grouped.put((AiTaskType) row[0], ((Number) row[1]).longValue());
        }

        assertTrue(!grouped.isEmpty(), "分组查询返回空结果 —— 解析失效（不得当成通过）");
        for (AiTaskType type : List.of(AiTaskType.JOB_GENERATION, AiTaskType.ATS_ANALYSIS)) {
            long perType = taskRepository.countAttemptsByTaskTypeAndCreatedAtAfter(type, TODAY_START);
            assertEquals(perType, grouped.getOrDefault(type, 0L),
                    type + " 的分组结果与单类型 count 不一致 —— 两条路径的口径已经漂移");
        }
        // 期望值（重试计次：retryCount<1 记 1，否则记 retryCount）
        assertEquals(1L + 1L + 2L, grouped.get(AiTaskType.JOB_GENERATION),
                "JOB_GENERATION 应为 1(retry0)+1(retry1)+2(retry2)，昨天那条不计入");
        assertEquals(1L + 3L, grouped.get(AiTaskType.ATS_ANALYSIS), "ATS_ANALYSIS 应为 1+3");
    }

    private void save(AiTaskType type, int retryCount, LocalDateTime createdAt) {
        AiTask task = new AiTask();
        task.setUserId(userId);
        task.setTaskType(type);
        task.setIdempotencyKey("agg-it-" + type + "-" + retryCount + "-" + createdAt.toEpochSecond(java.time.ZoneOffset.UTC));
        task.setRequestFingerprint("agg-it");
        task.setStatus(AiTaskStatus.FAILED);
        task.setConfirmationStatus(ConfirmationStatus.NOT_REQUIRED);
        task.setRetryCount(retryCount);
        task.setCreatedAt(createdAt);
        taskRepository.save(task);
    }
}
