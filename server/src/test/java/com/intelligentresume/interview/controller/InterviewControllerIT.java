package com.intelligentresume.interview.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
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
    private static String tokenA;
    private static String tokenB;
    private static long versionId;
    private static long jobId;
    private static long sessionId;

    @Test @Order(1)
    void prepareAndStartPlatformSession() throws Exception {
        tokenA = register("interview_a", "interview_a@example.com");
        tokenB = register("interview_b", "interview_b@example.com");
        long resumeId = id(postJson("/api/resumes", tokenA, "{\"title\":\"Interview resume\"}"));
        versionId = id(postJson("/api/resumes/" + resumeId + "/versions", tokenA, """
                {"resumeJson":{"basics":{"name":"Alice"},"work":[{"company":"ACME","description":"Java Spring delivery"}],"skills":[{"name":"Java"}]},"sourceType":"MANUAL"}
                """));
        jobId = id(postJson("/api/jobs", tokenA, """
                {"title":"Backend Engineer","companyName":"ACME","jdText":"Java Spring Boot MySQL engineering experience"}
                """));
        MvcResult result = mockMvc.perform(post("/api/interviews/start")
                        .header("Authorization", "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"PLATFORM_RESUME\",\"resumeVersionId\":%d,\"jobDescriptionId\":%d,\"interviewMode\":\"JD_TARGETED\"}".formatted(versionId, jobId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.interviewId").isNumber())
                .andExpect(jsonPath("$.data.firstQuestion").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS")).andReturn();
        sessionId = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("interviewId").asLong();
    }

    @Test @Order(2)
    void supportsExternalResumeAndRejectsForeignReferences() throws Exception {
        mockMvc.perform(post("/api/interviews/start").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"EXTERNAL_RESUME\",\"externalResumeText\":\"Java engineer with five years experience\",\"jobDescriptionId\":%d,\"interviewMode\":\"TECHNICAL\"}".formatted(jobId)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/interviews/start").header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"PLATFORM_RESUME\",\"resumeVersionId\":%d,\"jobDescriptionId\":%d,\"interviewMode\":\"JD_TARGETED\"}".formatted(versionId, jobId)))
                .andExpect(status().isNotFound());
    }

    @Test @Order(3)
    void serializesConcurrentAnswersIntoDistinctRounds() throws Exception {
        long concurrentSessionId = startSession();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<MvcResult> submitAnswer = () -> {
                ready.countDown();
                start.await();
                return mockMvc.perform(post("/api/interviews/" + concurrentSessionId + "/answer")
                                .header("Authorization", "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"answer\":\"In the situation I led a Java Spring project and achieved a 30 percent result.\"}"))
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
        Assertions.assertNotEquals(records.get(0).getQuestionText(), records.get(1).getQuestionText());
    }

    @Test @Order(4)
    void persistsRoundsCompletesAndBuildsReport() throws Exception {
        for (int round = 0; round < 3; round++) {
            MvcResult result = mockMvc.perform(post("/api/interviews/" + sessionId + "/answer")
                            .header("Authorization", "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"answer\":\"In the situation I led a Java Spring project, took action to improve MySQL and achieved a 30 percent result.\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.recordId").isNumber())
                    .andExpect(jsonPath("$.data.roundScore").isNumber())
                    .andExpect(jsonPath("$.data.feedback.strengths").isArray())
                    .andExpect(jsonPath("$.data.feedback.improvements").isArray()).andReturn();
            JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
            if (round < 2) Assertions.assertFalse(data.path("nextQuestion").isNull());
            else Assertions.assertTrue(data.path("nextQuestion").isNull());
        }
        mockMvc.perform(get("/api/interviews/" + sessionId + "/report").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalScore").isNumber())
                .andExpect(jsonPath("$.data.summary").isNotEmpty())
                .andExpect(jsonPath("$.data.strengths").isArray())
                .andExpect(jsonPath("$.data.weaknesses").isArray())
                .andExpect(jsonPath("$.data.resumeSuggestions").isArray())
                .andExpect(jsonPath("$.data.expressionSuggestions").isArray());
        mockMvc.perform(post("/api/interviews/" + sessionId + "/answer").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"another answer\"}"))
                .andExpect(status().isConflict());
    }

    @Test @Order(5)
    void foreignUserCannotReadSession() throws Exception {
        mockMvc.perform(get("/api/interviews/" + sessionId + "/report").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
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
                        .header("Authorization", "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"PLATFORM_RESUME\",\"resumeVersionId\":%d,\"jobDescriptionId\":%d,\"interviewMode\":\"JD_TARGETED\"}".formatted(versionId, jobId)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("interviewId").asLong();
    }
}
