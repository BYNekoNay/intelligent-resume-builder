package com.intelligentresume.ai.task.service;

import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.domain.ConfirmationStatus;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 任务保留期压缩契约（ideation #26）。
 *
 * <p>锚定：只有「终态 + 非待确认 + 已超期 + 未压缩过」的任务被压缩为元数据，
 * 且压缩是一次性的（再跑一轮不再命中）。
 */
@SpringBootTest
@ActiveProfiles("test")
class AiTaskRetentionIT {

    @Autowired private AiTaskRepository taskRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AiTaskRetentionService retentionService;

    @Test
    @DisplayName("#26：超期终态任务压缩为元数据；待确认/进行中/未超期/已压缩任务保持原样")
    void purgeExpiredSnapshots_onlyCompressesEligibleTasks() {
        Long userId = createUser("ai_task_retention");

        Long expiredSuccess = insert(userId, "retention-old-success", AiTaskStatus.SUCCESS, null, 100);
        Long expiredFailed = insert(userId, "retention-old-failed", AiTaskStatus.FAILED, null, 100);
        Long pendingConfirmation = insert(userId, "retention-pending-confirm", AiTaskStatus.SUCCESS,
                ConfirmationStatus.PENDING, 100);
        Long freshSuccess = insert(userId, "retention-fresh-success", AiTaskStatus.SUCCESS, null, 1);
        Long oldRunning = insert(userId, "retention-old-running", AiTaskStatus.RUNNING, null, 100);
        Long alreadyPurged = insert(userId, "retention-already-purged", AiTaskStatus.SUCCESS, null, 100);
        AiTask purged = taskRepository.findById(alreadyPurged).orElseThrow();
        purged.setSnapshotPurged(true);
        taskRepository.saveAndFlush(purged);

        int firstRun = retentionService.purgeExpiredSnapshots();

        assertEquals(2, firstRun, "只有两条超期终态任务应被压缩");
        assertPurged(expiredSuccess);
        assertPurged(expiredFailed);
        assertKeepsContent(pendingConfirmation);
        assertKeepsContent(freshSuccess);
        assertKeepsContent(oldRunning);
        // 已压缩标记的行不参与本轮（数量断言已证明只压缩 2 条）；其内容保持原样即证明未被重复处理
        AiTask already = taskRepository.findById(alreadyPurged).orElseThrow();
        assertTrue(already.isSnapshotPurged(), "已压缩标记应保持");
        assertEquals("kept until purged", already.getResultJson().get("optimizedContent"),
                "已压缩标记的行不应被本轮再次处理");

        assertEquals(0, retentionService.purgeExpiredSnapshots(), "压缩是一次性的：再跑一轮不再命中");
    }

    private void assertPurged(Long taskId) {
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        assertTrue(task.isSnapshotPurged(), "应标记为已压缩");
        assertEquals(Map.of("_purged", true), task.getInputSnapshotJson(), "快照应替换为占位 JSON");
        assertNull(task.getResultJson(), "结果应清空");
    }

    private void assertKeepsContent(Long taskId) {
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        assertFalse(task.isSnapshotPurged(), "不应被压缩：" + taskId);
        assertEquals(Map.of("taskType", "RESUME_OPTIMIZE", "resumeVersionId", 11), task.getInputSnapshotJson(),
                "快照应保持原样：" + taskId);
        assertEquals("kept until purged", task.getResultJson().get("optimizedContent"),
                "结果应保持原样：" + taskId);
    }

    private Long insert(Long userId, String key, AiTaskStatus status,
                        ConfirmationStatus confirmationStatus, long daysAgo) {
        AiTask task = new AiTask();
        task.setUserId(userId);
        task.setTaskType(AiTaskType.RESUME_OPTIMIZE);
        task.setIdempotencyKey(key);
        task.setRequestFingerprint(key);
        task.setInputSnapshotJson(Map.of("taskType", "RESUME_OPTIMIZE", "resumeVersionId", 11));
        task.setResultJson(Map.of("optimizedContent", "kept until purged"));
        task.setStatus(status);
        task.setConfirmationStatus(confirmationStatus);
        task.setRetryCount(0);
        Long id = taskRepository.saveAndFlush(task).getId();
        // 实体的 @PrePersist 会把时间戳置为 now；用 SQL 回填为历史时间以模拟超期任务
        jdbcTemplate.update("UPDATE ai_task SET updated_at = ? WHERE id = ?",
                LocalDateTime.now().minusDays(daysAgo), id);
        return id;
    }

    private Long createUser(String usernamePrefix) {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername(usernamePrefix + "_" + suffix);
        user.setEmail(usernamePrefix + "-" + suffix + "@example.test");
        user.setPasswordHash("hash");
        userRepository.save(user);
        return user.getId();
    }
}