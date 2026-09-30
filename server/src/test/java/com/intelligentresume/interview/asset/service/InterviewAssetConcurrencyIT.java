package com.intelligentresume.interview.asset.service;

import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.interview.asset.dto.InterviewAssetRequest;
import com.intelligentresume.interview.asset.repository.InterviewAnswerAssetRepository;
import com.intelligentresume.interview.domain.EvaluationSource;
import com.intelligentresume.interview.domain.InterviewMode;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewSourceType;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 面试资产并发幂等集成测试（ideation #24）。
 *
 * <p>两线程同步起跑、对同一面试记录并发创建资产：无论谁先到，都必须只落一条资产，
 * 且两个请求返回同一资产 id（修复前：应用层「先查后插」双双通过 → 重复资产）。
 * 并发保护的实现是「先锁面试记录行 + 唯一索引兜底」。
 */
@SpringBootTest
@ActiveProfiles("test")
class InterviewAssetConcurrencyIT {

    @Autowired private InterviewAssetService assetService;
    @Autowired private InterviewAnswerAssetRepository assetRepository;
    @Autowired private InterviewRecordRepository recordRepository;
    @Autowired private InterviewSessionRepository sessionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("并发创建同一面试记录的资产: 只落一条,两个请求返回同一 id")
    void concurrentCreate_sameRecord_producesSingleAsset() throws Exception {
        Long userId = createUser();
        Long recordId = createCompletedRecord(userId);
        InterviewAssetRequest request = new InterviewAssetRequest(recordId, "问题", "回答", null,
                Map.of(), List.of("work"), List.of());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Long> createOnce = () -> {
            start.await();
            return assetService.create(request, userId).id();
        };
        Future<Long> first = pool.submit(createOnce);
        Future<Long> second = pool.submit(createOnce);
        start.countDown();
        Long firstId;
        Long secondId;
        try {
            firstId = first.get(30, TimeUnit.SECONDS);
            secondId = second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(firstId, secondId, "两个并发请求应返回同一资产（幂等）");
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interview_answer_asset WHERE user_id = ? AND interview_record_id = ?",
                Integer.class, userId, recordId);
        assertEquals(1, rows, "同一记录并发创建后只允许一条资产");
        assertTrue(assetRepository.findByUserIdAndInterviewRecordId(userId, recordId).isPresent());
    }

    private Long createUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("asset_race_" + suffix);
        user.setEmail("asset-race-" + suffix + "@example.test");
        user.setPasswordHash("hash");
        userRepository.save(user);
        return user.getId();
    }

    private Long createCompletedRecord(Long userId) {
        InterviewSession session = new InterviewSession();
        session.setUserId(userId);
        session.setSourceType(InterviewSourceType.EXTERNAL_RESUME);
        session.setInterviewMode(InterviewMode.TECHNICAL);
        session.setStatus(InterviewStatus.COMPLETED);
        session.setExecutionMode(com.intelligentresume.interview.domain.ExecutionMode.RULE);
        session.setCurrentQuestion("Question");
        sessionRepository.save(session);

        InterviewRecord record = new InterviewRecord();
        record.setSessionId(session.getId());
        record.setRoundNo(1);
        record.setQuestionText("Question");
        record.setAnswerText("Answer");
        record.setRoundScore(60);
        record.setFeedbackJson(Map.of());
        record.setEvaluationSource(EvaluationSource.RULE);
        recordRepository.saveAndFlush(record);
        return record.getId();
    }
}