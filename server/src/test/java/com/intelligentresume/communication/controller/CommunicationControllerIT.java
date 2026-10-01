package com.intelligentresume.communication.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.communication.domain.CommunicationTemplate;
import com.intelligentresume.communication.repository.CommunicationTemplateRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommunicationControllerIT {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CommunicationTemplateRepository templateRepository;
    private static String tokenA;
    private static String tokenB;
    private static long versionId;
    private static long jobId;
    private static long aiTaskId;

    @Test @Order(1)
    void prepare() throws Exception {
        tokenA = register("communication_a", "communication_a@example.com");
        tokenB = register("communication_b", "communication_b@example.com");
        long resumeId = id(postJson("/api/resumes", tokenA, "{\"title\":\"Communication resume\"}"));
        versionId = id(postJson("/api/resumes/" + resumeId + "/versions", tokenA, """
                {"resumeJson":{"basics":{"name":"Alice Chen"},"skills":[{"name":"Java"}]},"sourceType":"MANUAL"}
                """));
        jobId = id(postJson("/api/jobs", tokenA, "{\"title\":\"Platform Engineer\",\"companyName\":\"Example Systems\",\"jdText\":\"Java reliability\"}"));
        mockMvc.perform(post("/api/ai/consent").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "policyVersion":"v1.2.0",
                                  "providerCode":"bailian",
                                  "taskScopes":["COMMUNICATION_GENERATE"],
                                  "dataCategories":["RESUME","JOB_DESCRIPTION"],
                                  "noticeHash":"communication-ai-test"
                                }
                                """))
                .andExpect(status().isCreated());
    }

    @Test @Order(2)
    void generatesFactGroundedManualDraftContract() throws Exception {
        mockMvc.perform(post("/api/communications/generate").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeVersionId\":%d,\"jobDescriptionId\":%d,\"type\":\"COVER_LETTER\"}".formatted(versionId, jobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("COVER_LETTER"))
                .andExpect(jsonPath("$.data.draft").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("Alice Chen"),
                        org.hamcrest.Matchers.containsString("Platform Engineer"))))
                .andExpect(jsonPath("$.data.sentAutomatically").value(false))
                .andExpect(jsonPath("$.data.requiresManualConfirmation").value(true))
                .andExpect(jsonPath("$.data.generationSource").value("TEMPLATE"));
    }

    @Test @Order(3)
    void rejectsCrossUserResources() throws Exception {
        mockMvc.perform(post("/api/communications/generate").header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeVersionId\":%d,\"jobDescriptionId\":%d,\"type\":\"EMAIL\"}".formatted(versionId, jobId)))
                .andExpect(status().isNotFound());
    }

    @Test @Order(4)
    void generatesEnglishTemplateWhenRequested() throws Exception {
        mockMvc.perform(post("/api/communications/generate").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeVersionId\":%d,\"jobDescriptionId\":%d,\"type\":\"EMAIL\",\"outputLanguage\":\"EN\"}"
                                .formatted(versionId, jobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft").value(org.hamcrest.Matchers.startsWith("Subject:")))
                .andExpect(jsonPath("$.data.generationSource").value("TEMPLATE"));
    }

    @Test @Order(5)
    void createsIdempotentCommunicationAiTask() throws Exception {
        String body = "{\"resumeVersionId\":%d,\"jobDescriptionId\":%d,\"type\":\"COVER_LETTER\",\"outputLanguage\":\"EN\"}"
                .formatted(versionId, jobId);
        MvcResult first = mockMvc.perform(post("/api/communications/ai-generate")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", "communication-ai-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.taskType").value("COMMUNICATION_GENERATE"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();
        aiTaskId = objectMapper.readTree(first.getResponse().getContentAsString()).path("data").path("id").asLong();

        mockMvc.perform(post("/api/communications/ai-generate")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", "communication-ai-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(aiTaskId));
    }

    @Test @Order(6)
    void validatesOwnershipBeforeAiAuthorization() throws Exception {
        mockMvc.perform(post("/api/communications/ai-generate")
                        .header("Authorization", "Bearer " + tokenB)
                        .header("Idempotency-Key", "cross-user-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeVersionId\":%d,\"jobDescriptionId\":%d,\"type\":\"EMAIL\"}"
                                .formatted(versionId, jobId)))
                .andExpect(status().isNotFound());
    }

    @Test @Order(7)
    @DisplayName("#73/#25: 陈旧模板副本保存触发乐观锁；使用计数按原子自增累计")
    void templateOptimisticLockAndAtomicUsageCount() throws Exception {
        long templateId = id(postJson("/api/communications/templates", tokenA, """
                {"name":"My template","scene":"GENERAL","type":"COVER_LETTER",
                 "bodyText":"您好 {{candidateName}}，关注 {{companyName}} 的机会。","outputLanguage":"ZH_CN"}
                """));

        // 使用计数（#25）：两次引用同一模板保存草稿 → 原子自增累计为 2，无丢失
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/communications/drafts").header("Authorization", "Bearer " + tokenA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"resumeVersionId\":%d,\"jobDescriptionId\":%d,\"type\":\"COVER_LETTER\","
                                    .formatted(versionId, jobId)
                                    + "\"draftText\":\"draft\",\"templateId\":" + templateId + "}"))
                    .andExpect(status().isOk());
        }
        Integer usageCount = jdbcTemplate.queryForObject(
                "SELECT usage_count FROM communication_template WHERE id = ?", Integer.class, templateId);
        Assertions.assertEquals(2, usageCount);

        // 乐观锁（#73）：模拟另一并发事务已更新同一模板 → 陈旧副本保存被拒
        CommunicationTemplate stale = templateRepository.findById(templateId).orElseThrow();
        jdbcTemplate.update("UPDATE communication_template SET version = version + 1 WHERE id = ?", templateId);
        stale.setName("stale write must be rejected");
        Assertions.assertThrows(OptimisticLockingFailureException.class,
                () -> templateRepository.saveAndFlush(stale));

        // API 更新仍正常（服务端每次重新加载最新版本）
        mockMvc.perform(put("/api/communications/templates/" + templateId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"My template v2","scene":"GENERAL","type":"COVER_LETTER",
                                 "bodyText":"您好 {{candidateName}}，关注 {{companyName}} 的机会。","outputLanguage":"ZH_CN"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("My template v2"));
    }

    @Test @Order(8)
    @DisplayName("归档版本不可消费：沟通（同步草稿与 AI 任务）返回 40901 且提示先恢复")
    void archivedVersionIsRejectedWithConflict() throws Exception {
        long resumeId = id(postJson("/api/resumes", tokenA, "{\"title\":\"Archived guard resume\"}"));
        long first = id(postJson("/api/resumes/" + resumeId + "/versions", tokenA, """
                {"resumeJson":{"basics":{"name":"Alice Chen"}},"sourceType":"MANUAL"}
                """));
        long second = id(postJson("/api/resumes/" + resumeId + "/versions", tokenA, """
                {"resumeJson":{"basics":{"name":"Alice Chen v2"}},"sourceType":"MANUAL"}
                """));

        // 让第二版成为当前版本，第一版才允许归档（当前版本不可归档）
        mockMvc.perform(patch("/api/resumes/" + resumeId + "/current-version")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\":%d}".formatted(second)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/resumes/" + resumeId + "/versions/" + first + "/archive")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        // 同步模板草稿：归档可逆 → 409 + 可操作文案，而不是「不存在」
        mockMvc.perform(post("/api/communications/generate").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeVersionId\":%d,\"jobDescriptionId\":%d,\"type\":\"EMAIL\"}".formatted(first, jobId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40902))
                .andExpect(jsonPath("$.message").value("该简历版本已归档，请先恢复后再发起沟通"));

        // AI 沟通任务：同一消费口径
        mockMvc.perform(post("/api/communications/ai-generate").header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", "archived-version-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeVersionId\":%d,\"jobDescriptionId\":%d,\"type\":\"EMAIL\"}".formatted(first, jobId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40902));

        // 模板预览同为「沟通」消费方：归档版本一律 409，不回落为「不存在」
        long templateId = id(postJson("/api/communications/templates", tokenA, """
                {"name":"Archived guard template","scene":"GENERAL","type":"EMAIL",
                 "bodyText":"您好 {{candidateName}}。","outputLanguage":"ZH_CN"}
                """));
        mockMvc.perform(get("/api/communications/templates/" + templateId + "/preview")
                        .header("Authorization", "Bearer " + tokenA)
                        .param("resumeVersionId", String.valueOf(first))
                        .param("jobDescriptionId", String.valueOf(jobId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40902))
                .andExpect(jsonPath("$.message").value("该简历版本已归档，请先恢复后再发起沟通"));
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
    private long id(MvcResult result) throws Exception { return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong(); }
}
