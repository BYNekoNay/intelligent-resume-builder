package com.intelligentresume.interview.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.repository.InterviewAiAttemptRepository;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InterviewControllerIT {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private InterviewRecordRepository recordRepository;
    @Autowired private InterviewAiAttemptRepository attemptRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    private static String tokenA;
    private static String tokenB;
    private static long versionId;
    private static long jobId;
    private static long sessionId;

    @Test @Order(1)
    void prepareAndStartPlatformSession() throws Exception {
        tokenA = register("interview_a", "interview_a@example.com");
        tokenB = register("interview_b", "interview_b@example.com");

        // 授权 AI 面试
        grantAiConsent(tokenA);

        long resumeId = id(postJson("/api/resumes", tokenA, "{\"title\":\"Interview resume\"}"));
        versionId = id(postJson("/api/resumes/" + resumeId + "/versions", tokenA, """
                {"resumeJson":{"basics":{"name":"Alice"},"work":[{"company":"ACME","description":"Java Spring delivery"}],"skills":[{"name":"Java"}]},"sourceType":"MANUAL"}
                """));
        jobId = id(postJson("/api/jobs", tokenA, """
                {"title":"Backend Engineer","companyName":"ACME","jdText":"Java Spring Boot MySQL engineering experience"}
                """));

        MvcResult result = mockMvc.perform(post("/api/interviews/start")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"PLATFORM_RESUME\",\"resumeVersionId\":%d,\"jobDescriptionId\":%d,\"interviewMode\":\"JD_TARGETED\",\"outputLanguage\":\"ZH_CN\"}".formatted(versionId, jobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interviewId").isNumber())
                .andExpect(jsonPath("$.data.status").isString())
                .andReturn();
        sessionId = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("interviewId").asLong();
        Assertions.assertEquals("ZH_CN", jdbcTemplate.queryForObject(
                "select output_language from interview_session where id = ?", String.class, sessionId));
    }

    @Test @Order(2)
    void supportsExternalResumeAndRejectsForeignReferences() throws Exception {
        mockMvc.perform(post("/api/interviews/start").header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"EXTERNAL_RESUME\",\"externalResumeText\":\"Java engineer with five years experience\",\"jobDescriptionId\":%d,\"interviewMode\":\"TECHNICAL\"}".formatted(jobId)))
                .andExpect(status().isOk());

        long foreignResumeId = id(postJson("/api/resumes", tokenB, "{\"title\":\"Foreign resume\"}"));
        long foreignVersionId = id(postJson("/api/resumes/" + foreignResumeId + "/versions", tokenB,
                "{\"resumeJson\":{\"basics\":{\"name\":\"Private user\"}},\"sourceType\":\"MANUAL\"}"));
        long foreignJobId = id(postJson("/api/jobs", tokenB,
                "{\"title\":\"Private job\",\"companyName\":\"Other Co\",\"jdText\":\"confidential role\"}"));

        mockMvc.perform(post("/api/interviews/start")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"PLATFORM_RESUME\",\"resumeVersionId\":%d,\"interviewMode\":\"TECHNICAL\"}".formatted(foreignVersionId)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/interviews/start")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"EXTERNAL_RESUME\",\"externalResumeText\":\"My resume\",\"jobDescriptionId\":%d,\"interviewMode\":\"TECHNICAL\"}".formatted(foreignJobId)))
                .andExpect(status().isNotFound());
    }

    @Test @Order(3)
    void serializesConcurrentAnswersIntoDistinctRounds() throws Exception {
        // 授权 userA
        grantAiConsent(tokenA);

        long concurrentSessionId = startSession();

        // AI 不可用(测试环境无 API Key) -> 切换到规则模式
        mockMvc.perform(post("/api/interviews/" + concurrentSessionId + "/continue-with-rules")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<MvcResult> submitAnswer = () -> {
                ready.countDown();
                start.await();
                return mockMvc.perform(post("/api/interviews/" + concurrentSessionId + "/answer")
                                .header("Authorization", "Bearer " + tokenA)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"answer\":\"Situation: I led a Java Spring project. Task: redesign the API. Action: I implemented caching and monitoring. Result: latency fell by 40 percent.\"}"))
                        .andExpect(status().isOk()).andReturn();
            };
            Future<MvcResult> first = executor.submit(submitAnswer);
            Future<MvcResult> second = executor.submit(submitAnswer);
            ready.await();
            start.countDown();
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        List<InterviewRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc(concurrentSessionId);
        Assertions.assertEquals(2, records.size());
        Assertions.assertEquals(List.of(1, 2), records.stream().map(InterviewRecord::getRoundNo).sorted().toList());
    }

    @Test @Order(4)
    void foreignUserCannotReadSession() throws Exception {
        mockMvc.perform(get("/api/interviews/" + sessionId + "/report").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test @Order(5)
    void rejectFinishWithoutAnyAnswer() throws Exception {
        grantAiConsent(tokenA);
        long newSessionId = startSession();
        mockMvc.perform(post("/api/interviews/" + newSessionId + "/finish")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().is4xxClientError());
    }

    @Test @Order(6)
    void ruleModeWorkflow() throws Exception {
        grantAiConsent(tokenA);
        long ruleSessionId = startSession();

        // AI 失败 -> 进入 AI_ACTION_REQUIRED
        mockMvc.perform(get("/api/interviews/" + ruleSessionId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AI_ACTION_REQUIRED"));

        // 切换到规则模式
        mockMvc.perform(post("/api/interviews/" + ruleSessionId + "/continue-with-rules")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AWAITING_ANSWER"))
                .andExpect(jsonPath("$.data.executionMode").value("RULE"));

        // 规则模式作答
        mockMvc.perform(post("/api/interviews/" + ruleSessionId + "/answer")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"Situation: I led a Java Spring project. Task: redesign the API. Action: I implemented caching and monitoring. Result: latency fell by 40 percent.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedQuestionCount").value(1));
    }

    // ==================== Order 10–15: additional integration tests ====================

    @Test @Order(10)
    void targetQuestionCount_boundary_min4_max12() throws Exception {
        grantAiConsent(tokenA);

        // target=4 → min=ceil(4*0.5)=2, max=floor(4*1.5)=6
        String body4 = objectMapper.writeValueAsString(Map.of(
                "sourceType", "PLATFORM_RESUME", "resumeVersionId", versionId,
                "jobDescriptionId", jobId, "interviewMode", "JD_TARGETED", "targetQuestionCount", 4));
        mockMvc.perform(post("/api/interviews/start")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetQuestionCount").value(4))
                .andExpect(jsonPath("$.data.minQuestionCount").value(2))
                .andExpect(jsonPath("$.data.maxQuestionCount").value(6));

        // target=12 → min=ceil(12*0.5)=6, max=floor(12*1.5)=18
        String body12 = objectMapper.writeValueAsString(Map.of(
                "sourceType", "PLATFORM_RESUME", "resumeVersionId", versionId,
                "jobDescriptionId", jobId, "interviewMode", "JD_TARGETED", "targetQuestionCount", 12));
        mockMvc.perform(post("/api/interviews/start")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body12))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetQuestionCount").value(12))
                .andExpect(jsonPath("$.data.minQuestionCount").value(6))
                .andExpect(jsonPath("$.data.maxQuestionCount").value(18));

        // target=2 (below @Min(4)) → Bean Validation rejects with 400
        String body2 = objectMapper.writeValueAsString(Map.of(
                "sourceType", "PLATFORM_RESUME", "resumeVersionId", versionId,
                "jobDescriptionId", jobId, "interviewMode", "JD_TARGETED", "targetQuestionCount", 2));
        mockMvc.perform(post("/api/interviews/start")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isBadRequest());

        // target=20 (above @Max(12)) → Bean Validation rejects with 400
        String body20 = objectMapper.writeValueAsString(Map.of(
                "sourceType", "PLATFORM_RESUME", "resumeVersionId", versionId,
                "jobDescriptionId", jobId, "interviewMode", "JD_TARGETED", "targetQuestionCount", 20));
        mockMvc.perform(post("/api/interviews/start")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body20))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(11)
    void rejectStartWithoutConsent() throws Exception {
        // Register a brand-new user who has NOT granted AI consent
        String freshToken = register("no_consent_user", "no_consent@example.com");

        long resumeId = id(postJson("/api/resumes", freshToken, "{\"title\":\"NC resume\"}"));
        long ncVersionId = id(postJson("/api/resumes/" + resumeId + "/versions", freshToken,
                "{\"resumeJson\":{\"basics\":{\"name\":\"NC\"}},\"sourceType\":\"MANUAL\"}"));
        long ncJobId = id(postJson("/api/jobs", freshToken,
                "{\"title\":\"NC Job\",\"companyName\":\"NC Co\",\"jdText\":\"testing\"}"));

        // Start interview WITHOUT granting consent — must NOT reach AWAITING_ANSWER
        MvcResult result = mockMvc.perform(post("/api/interviews/start")
                        .header("Authorization", "Bearer " + freshToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"PLATFORM_RESUME\",\"resumeVersionId\":%d,\"jobDescriptionId\":%d,\"interviewMode\":\"JD_TARGETED\"}".formatted(ncVersionId, ncJobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AI_ACTION_REQUIRED"))
                .andExpect(jsonPath("$.data.aiFailure.reauthorizationRequired").value(true))
                .andReturn();

        // The session must not have progressed past AI_ACTION_REQUIRED
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        Assertions.assertEquals("AI_ACTION_REQUIRED", data.path("status").asText());
        Assertions.assertTrue(data.path("aiFailure").path("reauthorizationRequired").asBoolean());
    }

    @Test @Order(12)
    void idempotentStartReplay() throws Exception {
        grantAiConsent(tokenA);
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = "{\"sourceType\":\"PLATFORM_RESUME\",\"resumeVersionId\":%d,\"jobDescriptionId\":%d,\"interviewMode\":\"JD_TARGETED\"}".formatted(versionId, jobId);

        // First call — enters AI_ACTION_REQUIRED (no API key) then switch to rules
        MvcResult firstResult = mockMvc.perform(post("/api/interviews/start")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interviewId").isNumber())
                .andReturn();
        long firstInterviewId = objectMapper.readTree(firstResult.getResponse().getContentAsString())
                .path("data").path("interviewId").asLong();

        // Switch to rules so the session is in a usable state
        mockMvc.perform(post("/api/interviews/" + firstInterviewId + "/continue-with-rules")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        // A failed provider response is still a completed API result and must replay.
        mockMvc.perform(post("/api/interviews/start")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interviewId").value(firstInterviewId));
    }

    @Test @Order(13)
    void userCanFinishAfterOneAnswer() throws Exception {
        grantAiConsent(tokenA);
        long sid = startSession();

        // Switch to rules mode
        mockMvc.perform(post("/api/interviews/" + sid + "/continue-with-rules")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AWAITING_ANSWER"));

        // Answer one question
        mockMvc.perform(post("/api/interviews/" + sid + "/answer")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"Situation: I led a Java Spring project. Task: redesign the API. Action: I implemented caching. Result: latency fell by 40 percent.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedQuestionCount").value(1));

        // User explicitly finishes the interview
        mockMvc.perform(post("/api/interviews/" + sid + "/finish")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.completionReason").value("USER_FINISHED"));
    }

    @Test @Order(14)
    void rejectFinishWithoutAnyAnswerInRuleMode() throws Exception {
        grantAiConsent(tokenA);
        long sid = startSession();

        // Switch to rules mode — no answers submitted yet
        mockMvc.perform(post("/api/interviews/" + sid + "/continue-with-rules")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AWAITING_ANSWER"))
                .andExpect(jsonPath("$.data.completedQuestionCount").value(0));

        // Finish must be rejected: the service requires at least 1 completed answer
        mockMvc.perform(post("/api/interviews/" + sid + "/finish")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().is4xxClientError());
    }

    @Test @Order(15)
    void getStateRecoversSession() throws Exception {
        grantAiConsent(tokenA);
        long sid = startSession();

        // Switch to rules mode
        mockMvc.perform(post("/api/interviews/" + sid + "/continue-with-rules")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        // Answer one question
        mockMvc.perform(post("/api/interviews/" + sid + "/answer")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"Situation: I led a Java Spring project. Task: redesign the API. Action: I implemented caching. Result: latency fell by 40 percent.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedQuestionCount").value(1));

        // GET state must reflect the same session data
        mockMvc.perform(get("/api/interviews/" + sid)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.interviewId").value(sid))
                .andExpect(jsonPath("$.data.completedQuestionCount").value(1))
                .andExpect(jsonPath("$.data.executionMode").value("RULE"))
                .andExpect(jsonPath("$.data.status").value("AWAITING_ANSWER"))
                .andExpect(jsonPath("$.data.currentQuestion").isString());
    }

    @Test @Order(16)
    void ruleAnswerReplayDoesNotCreateAnotherRound() throws Exception {
        grantAiConsent(tokenA);
        long sid = startSession();
        mockMvc.perform(post("/api/interviews/" + sid + "/continue-with-rules")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        String key = UUID.randomUUID().toString();
        String body = "{\"answer\":\"Situation: I led a migration. Task: reduce risk. Action: I added staged rollout checks. Result: incidents fell by 30 percent.\"}";
        mockMvc.perform(post("/api/interviews/" + sid + "/answer")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedQuestionCount").value(1));

        mockMvc.perform(post("/api/interviews/" + sid + "/answer")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedQuestionCount").value(1));
    }

    @Test @Order(17)
    void retryConsumesTheSixtiethCallAndRejectsTheSixtyFirst() throws Exception {
        grantAiConsent(tokenA);
        long sid = startSession();
        var attempt = attemptRepository.findAllBySessionId(sid).get(0);
        long used = attemptRepository.sumAttemptCountByUserIdAndCreatedAtAfter(
                attempt.getUserId(), LocalDate.now().atStartOfDay());
        Assertions.assertTrue(used < 59, "test setup must remain below the quota");
        attempt.setAttemptCount(attempt.getAttemptCount() + (int) (59 - used));
        attempt.setRetryable(true);
        attemptRepository.saveAndFlush(attempt);

        mockMvc.perform(post("/api/interviews/" + sid + "/ai/retry")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AI_ACTION_REQUIRED"));
        Assertions.assertEquals(60, attemptRepository.sumAttemptCountByUserIdAndCreatedAtAfter(
                attempt.getUserId(), LocalDate.now().atStartOfDay()));
        var retryableAttempt = attemptRepository.findById(attempt.getId()).orElseThrow();
        retryableAttempt.setRetryable(true);
        attemptRepository.saveAndFlush(retryableAttempt);

        mockMvc.perform(post("/api/interviews/" + sid + "/ai/retry")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiFailure.messageCode").value("RATE_LIMITED"));
        Assertions.assertEquals(60, attemptRepository.sumAttemptCountByUserIdAndCreatedAtAfter(
                attempt.getUserId(), LocalDate.now().atStartOfDay()));
    }

    @Test @Order(18)
    void getStateTakesOverAStaleProcessingAttempt() throws Exception {
        grantAiConsent(tokenA);
        long sid = startSession();
        var attempt = attemptRepository.findAllBySessionId(sid).get(0);
        jdbcTemplate.update("update interview_ai_attempt set status = 'PROCESSING', updated_at = ? where id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusSeconds(80)), attempt.getId());
        jdbcTemplate.update("update interview_session set status = 'GENERATING_QUESTION' where id = ?", sid);

        mockMvc.perform(get("/api/interviews/" + sid)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AI_ACTION_REQUIRED"))
                .andExpect(jsonPath("$.data.aiFailure.messageCode").value("PROCESSING_TIMEOUT"))
                .andExpect(jsonPath("$.data.aiFailure.retryable").value(true));
    }

    @Test @Order(19)
    void lastEvaluationContainsTheAnsweredQuestionSnapshot() throws Exception {
        grantAiConsent(tokenA);
        long sid = startSession();
        mockMvc.perform(post("/api/interviews/" + sid + "/continue-with-rules")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        String answer = "Situation: a release was unstable. Task: reduce failures. Action: I added canary checks. Result: failures fell by 40 percent.";
        mockMvc.perform(post("/api/interviews/" + sid + "/answer")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("answer", answer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastEvaluation.recordId").isNumber())
                .andExpect(jsonPath("$.data.lastEvaluation.questionText").isNotEmpty())
                .andExpect(jsonPath("$.data.lastEvaluation.answerText").value(answer));
    }

    @Test @Order(20)
    void rejectFinishWhileAnswerEvaluationIsInProgress() throws Exception {
        grantAiConsent(tokenA);
        long sid = startSession();
        mockMvc.perform(post("/api/interviews/" + sid + "/continue-with-rules")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/interviews/" + sid + "/answer")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"Situation: I investigated latency. Action: I fixed the query. Result: P99 fell by 30 percent.\"}"))
                .andExpect(status().isOk());
        jdbcTemplate.update("update interview_session set status = 'EVALUATING_ANSWER' where id = ?", sid);

        mockMvc.perform(post("/api/interviews/" + sid + "/finish")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isConflict());
    }

    @Test @Order(21)
    void rejectBlankAnswerAndInvalidIdempotencyKeys() throws Exception {
        long sid = startSession();

        mockMvc.perform(post("/api/interviews/" + sid + "/answer")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"   \"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/interviews/start")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", "x".repeat(65))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"PLATFORM_RESUME\",\"resumeVersionId\":%d,\"interviewMode\":\"TECHNICAL\"}".formatted(versionId)))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(22)
    void ruleFallbackReusesFailedAnswerAttemptWithFreshClientKey() throws Exception {
        grantAiConsent(tokenA);
        long sid = startSession();
        mockMvc.perform(post("/api/interviews/" + sid + "/continue-with-rules")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
        jdbcTemplate.update("update interview_session set execution_mode = 'AI', status = 'AWAITING_ANSWER' where id = ?", sid);

        String answer = "Situation: a database migration was risky. Task: preserve availability. Action: I used a canary rollout. Result: zero downtime.";
        mockMvc.perform(post("/api/interviews/" + sid + "/answer")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("answer", answer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AI_ACTION_REQUIRED"));

        mockMvc.perform(post("/api/interviews/" + sid + "/continue-with-rules")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/interviews/" + sid + "/answer")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("answer", answer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedQuestionCount").value(1));

        Assertions.assertEquals(1, recordRepository.countBySessionId(sid));
        Assertions.assertEquals(2, attemptRepository.findAllBySessionId(sid).size(),
                "initial-question and reused answer attempts should be the only attempts");
    }

    private void grantAiConsent(String token) throws Exception {
        mockMvc.perform(post("/api/ai/consent")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyVersion\":\"v1.2.0\",\"providerCode\":\"bailian\",\"taskScopes\":[\"INTERVIEW_COACH\"],\"dataCategories\":[\"RESUME\",\"INTERVIEW_ANSWER\",\"JOB_DESCRIPTION\"],\"noticeHash\":\"test-hash\"}"))
                .andExpect(status().isCreated());
    }

    private String register(String username, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"correcthorse\"}".formatted(username, email)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }
    private MvcResult postJson(String path, String token, String json) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn();
    }
    private long id(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private long startSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/interviews/start")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"PLATFORM_RESUME\",\"resumeVersionId\":%d,\"jobDescriptionId\":%d,\"interviewMode\":\"JD_TARGETED\"}".formatted(versionId, jobId)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("interviewId").asLong();
    }
}
