package com.intelligentresume.interview.repository;

import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.interview.domain.CompletionReason;
import com.intelligentresume.interview.domain.ExecutionMode;
import com.intelligentresume.interview.domain.InterviewMode;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewSourceType;
import com.intelligentresume.interview.domain.InterviewStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史面试会话摘要投影契约（ideation #50）。
 *
 * <p>投影只取元数据列（不加载 external_resume_text / current_question 等大字段），
 * 本测试锚定：JPQL 可执行、字段映射正确、状态与归属过滤生效。
 */
@SpringBootTest
@ActiveProfiles("test")
class InterviewSessionSummaryProjectionIT {

    @Autowired private UserRepository userRepository;
    @Autowired private InterviewSessionRepository sessionRepository;

    @Test
    @DisplayName("摘要投影只返回本人 COMPLETED 会话并映射元数据")
    void summaryProjection_filtersOwnershipAndStatus() {
        Long ownerId = createUser("summary_projection_owner");
        Long otherId = createUser("summary_projection_other");

        InterviewSession completed = session(ownerId, InterviewStatus.COMPLETED, "resume text ".repeat(50));
        session(ownerId, InterviewStatus.AWAITING_ANSWER, "in progress");
        session(otherId, InterviewStatus.COMPLETED, "other user");

        List<InterviewSessionRepository.SessionSummaryProjection> summaries =
                sessionRepository.findCompletedSummariesByUserId(ownerId, InterviewStatus.COMPLETED, null);

        assertEquals(1, summaries.size());
        InterviewSessionRepository.SessionSummaryProjection summary = summaries.get(0);
        assertEquals(completed.getId(), summary.getId());
        assertEquals(InterviewSourceType.EXTERNAL_RESUME, summary.getSourceType());
        assertEquals(InterviewMode.TECHNICAL, summary.getInterviewMode());
        assertEquals(ExecutionMode.RULE, summary.getExecutionMode());
        assertEquals(CompletionReason.USER_FINISHED, summary.getCompletionReason());
        assertEquals(6, summary.getTargetQuestionCount());
        assertTrue(summary.getUpdatedAt() != null);
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

    private InterviewSession session(Long userId, InterviewStatus status, String resumeText) {
        InterviewSession session = new InterviewSession();
        session.setUserId(userId);
        session.setSourceType(InterviewSourceType.EXTERNAL_RESUME);
        session.setInterviewMode(InterviewMode.TECHNICAL);
        session.setExecutionMode(ExecutionMode.RULE);
        session.setCompletionReason(CompletionReason.USER_FINISHED);
        session.setTargetQuestionCount(6);
        session.setStatus(status);
        session.setExternalResumeText(resumeText);
        session.setCurrentQuestion("Question");
        sessionRepository.save(session);
        return session;
    }
}