package com.intelligentresume.ats.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.preview.AiPreviewGuard;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.ats.repository.AtsCheckResultRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 4 片：ATS_ANALYSIS 的发送前预览与令牌强制（AC-R32-9 / 10 / 12）。
 *
 * <p>与前三片不同，ATS 的预览**按 checkId**：`AtsAiPromptBuilder` 会把整个 input
 * 序列化进 prompt 正文，其中含 `atsCheckResultId`（worker 用它写回结果）；
 * 预览若没有真实 id，组装结果就与发送时不同，令牌必然对不上（计划 §7.8）。
 *
 * <p>另一个前提：无密钥环境会在 `isAvailable()` 处短路成 `AI_DISABLED`，
 * 令牌校验根本走不到 —— 所以本类注入 `app.ai.bailian.api-key`，让 AI 路径真的被执行。
 */
@SpringBootTest(properties = "app.ai.bailian.api-key=test-key")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AtsPreviewProtocolIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AtsCheckResultRepository atsRepository;
    @Autowired private AiTaskRepository taskRepository;

    private final List<Long> createdCheckIds = new ArrayList<>();
    private final List<Long> createdTaskIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdTaskIds.forEach(id -> taskRepository.findById(id).ifPresent(taskRepository::delete));
        createdCheckIds.forEach(id -> atsRepository.findById(id).ifPresent(atsRepository::delete));
        createdTaskIds.clear();
        createdCheckIds.clear();
    }

    @Test
    @DisplayName("R32：先预览再启动 ATS AI → 200 且产生 aiTaskId")
    void previewThenRetryCreatesAiTask() throws Exception {
        String token = register("ats_preview_happy");
        grantConsent(token);
        long checkId = createRulesCheck(token);
        long before = taskRepository.count();

        String previewToken = previewTokenOf(preview(token, checkId));
        MvcResult retried = mockMvc.perform(post("/api/ats/checks/" + checkId + "/ai-retry")
                        .header("Authorization", "Bearer " + token)
                        .header(AiPreviewGuard.HEADER, previewToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiTaskId").isNumber())
                .andReturn();

        assertEquals(before + 1, taskRepository.count(), "有效令牌应恰好产生一条任务");
        createdTaskIds.add(objectMapper.readTree(retried.getResponse().getContentAsString())
                .path("data").path("aiTaskId").asLong());
    }

    @Test
    @DisplayName("R32：不带预览令牌启动 ATS AI → 40001，且 ai_task 零新增")
    void retryWithoutPreviewTokenIsRejected() throws Exception {
        String token = register("ats_preview_missing");
        grantConsent(token);
        long checkId = createRulesCheck(token);
        long before = taskRepository.count();

        mockMvc.perform(post("/api/ats/checks/" + checkId + "/ai-retry")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
        assertEquals(before, taskRepository.count(), "缺令牌不得产生 ai_task 行");
    }

    @Test
    @DisplayName("R32：令牌来自另一次检查 → 40901，且 ai_task 零新增")
    void retryWithStalePreviewTokenIsRejected() throws Exception {
        String token = register("ats_preview_stale");
        grantConsent(token);
        long firstCheckId = createRulesCheck(token);
        long secondJobId = createJob(token, "Platform Engineer", "Java Kafka reliability");
        long secondCheckId = createRulesCheck(token, secondJobId);

        String tokenOfFirstCheck = previewTokenOf(preview(token, firstCheckId));
        long before = taskRepository.count();

        mockMvc.perform(post("/api/ats/checks/" + secondCheckId + "/ai-retry")
                        .header("Authorization", "Bearer " + token)
                        .header(AiPreviewGuard.HEADER, tokenOfFirstCheck)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
        assertEquals(before, taskRepository.count(), "令牌不符不得产生 ai_task 行");
    }

    @Test
    @DisplayName("R32：预览只读（不落库），同一检查令牌稳定")
    void previewIsReadOnly() throws Exception {
        String token = register("ats_preview_readonly");
        grantConsent(token);
        long checkId = createRulesCheck(token);
        long before = taskRepository.count();

        String first = previewTokenOf(preview(token, checkId));
        String second = previewTokenOf(preview(token, checkId));

        assertEquals(before, taskRepository.count(), "预览不得写库（AC-R32-12）");
        assertEquals(first, second, "同一检查的预览令牌必须稳定");
    }

    // ------------------------------------------------------------------ 辅助

    private MvcResult preview(String token, Long checkId) throws Exception {
        return mockMvc.perform(post("/api/ats/checks/" + checkId + "/preview")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages").isArray())
                .andExpect(jsonPath("$.data.previewToken").isNotEmpty())
                .andReturn();
    }

    private String previewTokenOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("data").path("previewToken").asText();
    }

    /** ATS 预览的前提是 check 已存在，所以先建一条纯规则检查。 */
    private long createRulesCheck(String token) throws Exception {
        long jobId = createJob(token, "Backend Engineer", "Java Spring Boot MySQL Redis");
        return createRulesCheck(token, jobId);
    }

    private long createRulesCheck(String token, long jobId) throws Exception {
        long resumeId = createResume(token, "ATS preview resume");
        long versionId = createVersion(token, resumeId);
        return runCheck(token, versionId, jobId, false);
    }

    private long runCheck(String token, long versionId, long jobId, boolean useAi) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ats/check")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeVersionId\":%d,\"jobDescriptionId\":%d,\"useAi\":%b}"
                                .formatted(versionId, jobId, useAi)))
                .andExpect(status().isOk())
                .andReturn();
        long id = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
        createdCheckIds.add(id);
        return id;
    }

    /** 纯规则检查不带令牌也必须成功 —— 只有"启动 AI"才需要预览确认。 */
    @Test
    @DisplayName("R32：useAi=false 的检查不受预览令牌影响")
    void rulesOnlyCheckDoesNotRequirePreviewToken() throws Exception {
        String token = register("ats_preview_rules");
        grantConsent(token);
        long resumeId = createResume(token, "ATS rules resume");
        long versionId = createVersion(token, resumeId);
        long jobId = createJob(token, "Backend Engineer", "Java Spring Boot MySQL Redis");

        long checkId = runCheck(token, versionId, jobId, false);
        mockMvc.perform(get("/api/ats/checks/" + checkId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisStatus").value("RULES_ONLY"));
    }

    private long createResume(String token, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/resumes").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private long createVersion(String token, long resumeId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/resumes/" + resumeId + "/versions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeJson\":{\"basics\":{\"name\":\"Alice\",\"summary\":\"Java Spring Boot engineer\"},"
                                + "\"work\":[{\"company\":\"ACME\",\"description\":\"MySQL reliability\"}],"
                        + "\"skills\":[{\"name\":\"Java\"}]},\"sourceType\":\"MANUAL\"}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private long createJob(String token, String title, String jdText) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/jobs").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"jdText\":\"" + jdText + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private void grantConsent(String token) throws Exception {
        mockMvc.perform(post("/api/ai/consent").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "policyVersion":"v1.2.0",
                                  "providerCode":"bailian",
                                  "taskScopes":["ATS_ANALYSIS"],
                                  "dataCategories":["RESUME","JOB_DESCRIPTION"],
                                  "noticeHash":"ats-preview-test"
                                }
                                """))
                .andExpect(status().isCreated());
    }

    private String register(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"email\":\"%s@example.com\",\"password\":\"correcthorse\"}"
                                .formatted(username, username)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }
}
