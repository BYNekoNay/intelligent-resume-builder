package com.intelligentresume.interview.repository;

import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.interview.domain.EvaluationSource;
import com.intelligentresume.interview.domain.InterviewMode;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewSourceType;
import com.intelligentresume.interview.domain.InterviewStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 面试记录排序契约锚定（#75）。
 *
 * <p>历史实现按 {@code created_at} 排序，同毫秒写入时顺序无稳定契约。现在按
 * {@code round_no} 升序（V18 的 {@code uq_interview_record_session_round} 保证
 * 同 session 内唯一），本测试故意**倒序写入**：若排序仍依赖写入时间，结果会是
 * [2, 1]；按轮次排序则恒为 [1, 2]。
 */
@SpringBootTest
@ActiveProfiles("test")
class InterviewRecordOrderingIT {

    @Autowired private UserRepository userRepository;
    @Autowired private InterviewSessionRepository sessionRepository;
    @Autowired private InterviewRecordRepository recordRepository;

    @Test
    @DisplayName("记录按 round_no 升序返回，与写入先后无关")
    void records_areOrderedByRoundNumber_notInsertionOrder() {
        Long sessionId = seedRecordsWrittenOutOfRoundOrder();

        List<InterviewRecord> records =
                recordRepository.findBySessionIdOrderByRoundNoAscIdAsc(sessionId);

        assertEquals(List.of(1, 2), records.stream().map(InterviewRecord::getRoundNo).toList());
    }

    @Test
    @DisplayName("评分投影查询（历史服务使用）可执行且按轮次返回")
    void scoreProjectionQuery_executesAndOrdersByRound() {
        Long sessionId = seedRecordsWrittenOutOfRoundOrder();

        List<InterviewRecordRepository.ScoreProjection> scores =
                recordRepository.findScoresBySessionIdInOrderByRoundNoAscIdAsc(List.of(sessionId));

        assertEquals(List.of(60, 70), scores.stream()
                .map(InterviewRecordRepository.ScoreProjection::getRoundScore).toList());
    }

    /** 先写第 2 轮、再写第 1 轮：createdAt 顺序与轮次顺序相反。 */
    private Long seedRecordsWrittenOutOfRoundOrder() {
        // 同一 Spring 上下文里的 H2 库在测试类间共享，用户名/邮箱需唯一
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("record_order_" + suffix);
        user.setEmail("record-order-" + suffix + "@example.test");
        user.setPasswordHash("hash");
        userRepository.save(user);

        InterviewSession session = new InterviewSession();
        session.setUserId(user.getId());
        session.setSourceType(InterviewSourceType.EXTERNAL_RESUME);
        session.setInterviewMode(InterviewMode.TECHNICAL);
        session.setStatus(InterviewStatus.AWAITING_ANSWER);
        session.setCurrentQuestion("Question");
        sessionRepository.save(session);

        recordRepository.saveAndFlush(record(session.getId(), 2, "Q2", 70));
        recordRepository.saveAndFlush(record(session.getId(), 1, "Q1", 60));
        return session.getId();
    }

    private InterviewRecord record(Long sessionId, int roundNo, String question, int score) {
        InterviewRecord record = new InterviewRecord();
        record.setSessionId(sessionId);
        record.setRoundNo(roundNo);
        record.setQuestionText(question);
        record.setAnswerText("answer");
        record.setRoundScore(score);
        record.setFeedbackJson(Map.of());
        record.setEvaluationSource(EvaluationSource.RULE);
        return record;
    }
}