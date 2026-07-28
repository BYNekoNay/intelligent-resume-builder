package com.intelligentresume.ai.task.repository;

import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.ai.worker.TaskLeaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AiTaskRepositoryIT {
    @Autowired private AiTaskRepository taskRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TaskLeaseService leaseService;

    @Test
    void countsPendingTasksOnceAndStartedTasksByActualAttemptCount() {
        User user = new User();
        user.setUsername("quota-repository-user");
        user.setEmail("quota-repository@example.invalid");
        user.setPasswordHash("not-used");
        user = userRepository.saveAndFlush(user);

        taskRepository.save(task(user.getId(), "pending", 0));
        taskRepository.save(task(user.getId(), "first-attempt", 1));
        taskRepository.save(task(user.getId(), "third-attempt", 3));
        taskRepository.flush();

        long attempts = taskRepository.countAttemptsByUserIdAndTaskTypeAndCreatedAtAfter(
                user.getId(), AiTaskType.INLINE_OPTIMIZE, LocalDateTime.now().minusMinutes(1));

        assertEquals(5L, attempts);
    }

    @Test
    void staleWorkerCannotRenewOrOverwriteTheCurrentLeaseOwner() {
        User user = new User();
        user.setUsername("lease-owner-user");
        user.setEmail("lease-owner@example.invalid");
        user.setPasswordHash("not-used");
        user = userRepository.saveAndFlush(user);

        AiTask task = task(user.getId(), "lease-owner", 2);
        task.setStatus(AiTaskStatus.RUNNING);
        task.setLeaseOwner("worker-2");
        task.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(1));
        task = taskRepository.saveAndFlush(task);

        assertFalse(leaseService.renew(task.getId(), "worker-1"));
        assertFalse(leaseService.releaseSuccess(task, "worker-1", Map.of("output", "stale")));

        AiTask stored = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals(AiTaskStatus.RUNNING, stored.getStatus());
        assertEquals("worker-2", stored.getLeaseOwner());
        assertNull(stored.getResultJson());
        assertTrue(leaseService.renew(task.getId(), "worker-2"));
    }

    private AiTask task(Long userId, String key, int attempts) {
        AiTask task = new AiTask();
        task.setUserId(userId);
        task.setTaskType(AiTaskType.INLINE_OPTIMIZE);
        task.setIdempotencyKey(key);
        task.setRequestFingerprint(key);
        task.setInputSnapshotJson(Map.of("taskType", AiTaskType.INLINE_OPTIMIZE.name()));
        task.setStatus(AiTaskStatus.PENDING);
        task.setRetryCount(attempts);
        return task;
    }
}
