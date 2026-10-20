package com.intelligentresume.application.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApplicationControllerIT {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private static String tokenA;
    private static String tokenB;
    private static long jobId;
    private static long resumeId;
    private static long versionId;
    private static long applicationId;

    @Test
    @Order(1)
    void prepareOwnedResources() throws Exception {
        tokenA = register("application_a", "application_a@example.com");
        tokenB = register("application_b", "application_b@example.com");
        jobId = id(postJson("/api/jobs", tokenA, """
                {"title":"Platform Engineer","companyName":"Example","jdText":"Java Spring reliability"}
                """));
        resumeId = id(postJson("/api/resumes", tokenA, "{\"title\":\"Platform resume\"}"));
        versionId = id(postJson("/api/resumes/" + resumeId + "/versions", tokenA, """
                {"resumeJson":{"basics":{"name":"Alice"},"work":[]},"sourceType":"MANUAL"}
                """));
    }

    @Test
    @Order(2)
    void createReturnsCompleteRecordAndListReturnsSummary() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/applications")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobDescriptionId":%d,"resumeVersionId":%d,"status":"DRAFT","coverLetterText":"Hello"}
                                """.formatted(jobId, versionId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.jobDescriptionId").value(jobId))
                .andExpect(jsonPath("$.data.resumeVersionId").value(versionId))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.coverLetterText").value("Hello"))
                .andExpect(jsonPath("$.data.statusHistory", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data.statusHistory[0].fromStatus").isEmpty())
                .andExpect(jsonPath("$.data.statusHistory[0].toStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data.statusHistory[0].changedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn();
        applicationId = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();

        // #50：列表为摘要——不返回草稿长文本（按需走详情），改为 draftCount 标记；
        // feedbackText 仍在摘要里（状态迁移接口按请求值覆盖备注，需携带现值避免拖拽清空）
        mockMvc.perform(get("/api/applications").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(applicationId))
                .andExpect(jsonPath("$.data[0].draftCount").value(1))
                .andExpect(jsonPath("$.data[0].coverLetterText").doesNotExist())
                .andExpect(jsonPath("$.data[0].emailBodyText").doesNotExist())
                .andExpect(jsonPath("$.data[0].openingMessageText").doesNotExist());
        mockMvc.perform(get("/api/applications").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        // #50：详情接口按需返回草稿长文本；跨用户不可见
        mockMvc.perform(get("/api/applications/" + applicationId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.coverLetterText").value("Hello"))
                .andExpect(jsonPath("$.data.statusHistory", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data.draftCount").doesNotExist());
        mockMvc.perform(get("/api/applications/" + applicationId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(3)
    void updateAndStatusTransitionUseOptimisticVersion() throws Exception {
        mockMvc.perform(put("/api/applications/" + applicationId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobDescriptionId":%d,"resumeVersionId":%d,"status":"DRAFT","emailBodyText":"Interview request","version":0}
                                """.formatted(jobId, versionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailBodyText").value("Interview request"))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(patch("/api/applications/" + applicationId + "/status")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPLIED\",\"version\":1,\"note\":\"Submitted via company site\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.appliedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(get("/api/applications/" + applicationId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusHistory", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.data.statusHistory[1].fromStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data.statusHistory[1].toStatus").value("APPLIED"))
                .andExpect(jsonPath("$.data.statusHistory[1].note").value("Submitted via company site"));

        mockMvc.perform(patch("/api/applications/" + applicationId + "/status")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DRAFT\",\"version\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));

        // PATCH 备注语义：发送即更新；缺席/null 保留现值（不关心备注的调用不再清空）；
        // 显式空串 = 清空（归一为 null，响应省略该字段）
        mockMvc.perform(patch("/api/applications/" + applicationId + "/status")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPLIED\",\"version\":2,\"feedbackText\":\"Recruiter replied\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.feedbackText").value("Recruiter replied"))
                .andExpect(jsonPath("$.data.version").value(3));

        mockMvc.perform(patch("/api/applications/" + applicationId + "/status")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPLIED\",\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.feedbackText").value("Recruiter replied"))
                // 无任何变更的 PATCH 不写库（dirty checking），乐观锁版本保持 3
                .andExpect(jsonPath("$.data.version").value(3));

        mockMvc.perform(patch("/api/applications/" + applicationId + "/status")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPLIED\",\"version\":3,\"feedbackText\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.feedbackText").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(4));
    }

    @Test
    @Order(4)
    void progressesThroughWorkflowAndMakesOnlyNoResponseReopenable() throws Exception {
        transition("SCREENING", 4);
        transition("INTERVIEWING", 5);
        transition("OFFERED", 6);
        transition("HIRED", 7);

        mockMvc.perform(patch("/api/applications/" + applicationId + "/status")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPLIED\",\"version\":8}"))
                .andExpect(status().isConflict());

        // A separate record exercises the no-response reopen path without undoing the hired record.
        long secondId = id(postJson("/api/applications", tokenA,
                "{\"jobDescriptionId\":%d,\"resumeVersionId\":%d}".formatted(jobId, versionId)));
        transition(secondId, "APPLIED", 0);
        transition(secondId, "NO_RESPONSE", 1);
        transition(secondId, "SCREENING", 2);
        mockMvc.perform(get("/api/applications/" + secondId).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusHistory", org.hamcrest.Matchers.hasSize(4)))
                .andExpect(jsonPath("$.data.statusHistory[2].toStatus").value("NO_RESPONSE"))
                .andExpect(jsonPath("$.data.statusHistory[3].fromStatus").value("NO_RESPONSE"));
    }

    @Test
    @Order(5)
    void statsReturnsOwnedAggregationAndIsolatesForeignUsers() throws Exception {
        // 前序用例将主记录置为 HIRED，第二条记录处于 SCREENING。
        mockMvc.perform(get("/api/applications/stats").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.byStatus", org.hamcrest.Matchers.hasSize(9)))
                .andExpect(jsonPath("$.data.byStatus[5].status").value("HIRED"))
                .andExpect(jsonPath("$.data.byStatus[5].count").value(1))
                // HIRED 计入已完成 offer 漏斗，因此当前面试/offer 转化率为 0.5。
                .andExpect(jsonPath("$.data.conversionRates.interviewingToOffered").value(0.5));
        // 归属隔离：用户 B 看不到用户 A 的投递统计
        mockMvc.perform(get("/api/applications/stats").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.byStatus", org.hamcrest.Matchers.hasSize(9)));
    }

    @Test
    @Order(6)
    void statsUnauthenticated_returns40101() throws Exception {
        mockMvc.perform(get("/api/applications/stats"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @Order(7)
    void crossUserCannotMutateAndOwnerCanDelete() throws Exception {
        mockMvc.perform(delete("/api/applications/" + applicationId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/applications/" + applicationId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/applications").header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].status").value("SCREENING"));
    }

    @Test
    @Order(8)
    void unauthenticated_returns40101() throws Exception {
        mockMvc.perform(get("/api/applications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    private String register(String username, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"correcthorse\"}".formatted(username, email)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }

    private MvcResult postJson(String url, String token, String body) throws Exception {
        return mockMvc.perform(post(url).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
    }

    private long id(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private void transition(String status, long version) throws Exception {
        transition(applicationId, status, version);
    }

    private void transition(long id, String status, long version) throws Exception {
        mockMvc.perform(patch("/api/applications/" + id + "/status")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"%s\",\"version\":%d}".formatted(status, version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(status));
    }
}
