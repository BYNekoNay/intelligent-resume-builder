package com.intelligentresume.interview.repository;

import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.interview.domain.AiAttemptOperationType;
import com.intelligentresume.interview.domain.AiAttemptStatus;
import com.intelligentresume.interview.domain.InterviewAiAttempt;
import com.intelligentresume.interview.domain.InterviewMode;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewSourceType;
import com.intelligentresume.interview.domain.InterviewStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 面试 AI 尝试「取最近一条」排序契约锚定（第二十五批）。
 *
 * <p>interview_ai_attempt.updated_at 是全库唯一的秒级 DATETIME（V20，其余表均毫秒
 * 精度），同一秒内不同 round/operation 可各写入一条 FAILED。aiFailure 展示
 * （stage/messageCode/retryable）与 PROCESSING 超时判定都依赖「取最近一条」；单键
 * 排序在并列时无稳定契约，必须由自增 id 兜底。本测试把两条 FAILED 的 updated_at
 * 统一为同一秒，断言返回后写入（id 更大）的那条。
 */
@SpringBootTest
@ActiveProfiles("test")
class InterviewAiAttemptOrderingIT {

    @Autowired private UserRepository userRepository;
    @Autowired private InterviewSessionRepository sessionRepository;
    @Autowired private InterviewAiAttemptRepository attemptRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("同一秒两条 FAILED 时取 id 更大的那条")
    void latestFailedAttempt_tieBreaksByIdDesc() {
        // 同一 Spring 上下文里的 H2 库在测试类间共享，用户名/邮箱需唯一
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("attempt_order_" + suffix);
        user.setEmail("attempt-order-" + suffix + "@example.test");
        user.setPasswordHash("hash");
        userRepository.save(user);

        InterviewSession session = new InterviewSession();
        session.setUserId(user.getId());
        session.setSourceType(InterviewSourceType.EXTERNAL_RESUME);
        session.setInterviewMode(InterviewMode.TECHNICAL);
        session.setStatus(InterviewStatus.AI_ACTION_REQUIRED);
        session.setCurrentQuestion("Question");
        sessionRepository.save(session);

        // 两条 FAILED 以不同 operation/round 满足两列唯一约束
        seedFailedAttempt(user.getId(), session.getId(),
                AiAttemptOperationType.INITIAL_QUESTION, "PROCESSING_TIMEOUT");
        Long later = seedFailedAttempt(user.getId(), session.getId(),
                AiAttemptOperationType.ANSWER_EVALUATION, "QUOTA_EXCEEDED");

        // 秒级并列：模拟同秒内的两次失败
        LocalDateTime sameSecond = LocalDateTime.now().withNano(0);
        jdbcTemplate.update(
                "UPDATE interview_ai_attempt SET updated_at = ?, created_at = ? WHERE session_id = ?",
                sameSecond, sameSecond, session.getId());

        InterviewAiAttempt latest = attemptRepository
                .findFirstBySessionIdAndStatusOrderByUpdatedAtDescIdDesc(session.getId(), AiAttemptStatus.FAILED)
                .orElseThrow();

        assertEquals(later, latest.getId(), "同秒并列时应取 id 更大（最后写入）的那条");
        // 语义锚定：返回的必须是后写那条的错误码，否则 aiFailure 会误导用户
        assertEquals("QUOTA_EXCEEDED", latest.getErrorCode());
    }

    private Long seedFailedAttempt(Long userId, Long sessionId,
                                   AiAttemptOperationType operationType, String errorCode) {
        InterviewAiAttempt attempt = new InterviewAiAttempt();
        attempt.setUserId(userId);
        attempt.setSessionId(sessionId);
        attempt.setOperationType(operationType);
        attempt.setRoundNo(1);
        attempt.setIdempotencyKey(UUID.randomUUID().toString());
        attempt.setRequestFingerprint("fp-" + UUID.randomUUID());
        attempt.setStatus(AiAttemptStatus.FAILED);
        attempt.setErrorCode(errorCode);
        attempt.setErrorMessage("boom " + errorCode);
        return attemptRepository.saveAndFlush(attempt).getId();
    }
}