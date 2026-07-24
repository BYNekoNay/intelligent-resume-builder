package com.intelligentresume.ai.confirmation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.ConfirmationStatus;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 来源确认控制器集成测试（MockMvc + H2 + Flyway）。
 * 覆盖：缺 Idempotency-Key 40001、跨用户 40401、confirm 成功、幂等重放、reject 成功。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfirmationControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AiTaskRepository taskRepository;

    private static String tokenA;
    private static String tokenB;
    private static Long materialIdA;
    private static Long jdIdA;
    private static Long resumeIdA;
    private static Long taskId;
    private static LocalDateTime taskUpdatedAt;

    // ---- 辅助方法 ----

    private String registerAndGetToken(String username, String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s","password":"%s"}
                                """.formatted(username, email, password)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("accessToken").asText();
    }

    // ---- 准备 ----

    @Test
    @Order(1)
    @DisplayName("准备: 注册用户 + 同意 + 资料 + 简历 + SUCCESS 任务")
    void setup() throws Exception {
        taskRepository.deleteAll();

        tokenA = registerAndGetToken("conf_user_a", "conf_user_a@example.com", "correcthorse");
        tokenB = registerAndGetToken("conf_user_b", "conf_user_b@example.com", "correcthorse");

        // 用户 A 授权
        mockMvc.perform(post("/api/ai/consent")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "policyVersion": "v1.0.0",
                                  "providerCode": "bailian",
                                  "taskScopes": ["JOB_GENERATION"],
                                  "dataCategories": ["resume"],
                                  "noticeHash": "hash"
                                }
                                """))
                .andExpect(status().isCreated());

        // 用户 A 创建资料
        MvcResult matResult = mockMvc.perform(post("/api/career-materials")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "materialType": "WORK_EXPERIENCE",
                                  "title": "Java开发经验",
                                  "contentJson": {"company": "测试公司", "role": "Java开发"},
                                  "sourceText": "负责 Spring Boot 微服务开发"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        materialIdA = objectMapper.readTree(matResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        // 用户 A 创建 JD
        MvcResult jdResult = mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Java后端工程师",
                                  "companyName": "某科技公司",
                                  "jdText": "负责 Spring Boot 微服务开发,熟悉 MySQL/Redis。"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        jdIdA = objectMapper.readTree(jdResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        // 用户 A 创建简历
        MvcResult resumeResult = mockMvc.perform(post("/api/resumes")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "我的简历"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        resumeIdA = objectMapper.readTree(resumeResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        // 创建 JOB_GENERATION 任务
        MvcResult taskResult = mockMvc.perform(post("/api/ai/generate-resume-for-job")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", "conf-test-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskType": "JOB_GENERATION",
                                  "targetResumeId": %d,
                                  "jobDescriptionId": %d,
                                  "input": {
                                    "includedMaterialIds": [%d],
                                    "preferredMaterialIds": [],
                                    "excludedMaterialIds": []
                                  }
                                }
                                """.formatted(resumeIdA, jdIdA, materialIdA)))
                .andExpect(status().isAccepted())
                .andReturn();
        taskId = objectMapper.readTree(taskResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        // 手动将任务设为 SUCCESS + PENDING 确认（模拟 worker 完成）
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        task.setStatus(AiTaskStatus.SUCCESS);
        task.setConfirmationStatus(ConfirmationStatus.PENDING);

        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("basics", new LinkedHashMap<>(Map.of(
                "name", "张三", "_source", Map.of("materialId", materialIdA))));
        draft.put("work", List.of(new LinkedHashMap<>(Map.of(
                "company", "测试公司",
                "highlights", List.of(
                        "负责 Spring Boot 微服务开发",
                        new LinkedHashMap<>(Map.of("_pending", Map.of("reason", "需补充量化成果")))
                ),
                "_source", Map.of("materialId", materialIdA)
        ))));

        Map<String, Object> resultJson = new LinkedHashMap<>();
        resultJson.put("draftResumeJson", draft);
        resultJson.put("selected", List.of(
                Map.of("materialId", materialIdA, "outputPath", "work[0]",
                        "selectedReason", "USER_FIXED")));
        task.setResultJson(resultJson);

        taskRepository.save(task);

        // 重新读取以获取 updatedAt
        task = taskRepository.findById(taskId).orElseThrow();
        taskUpdatedAt = task.getUpdatedAt();
    }

    // ---- 1. 缺 Idempotency-Key ----

    @Test
    @Order(2)
    @DisplayName("POST /confirm 缺 Idempotency-Key 返回 40001")
    void postConfirm_missingIdempotencyKey_40001() throws Exception {
        mockMvc.perform(post("/api/ai/tasks/" + taskId + "/confirm")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskUpdatedAt": "%s",
                                  "items": [
                                    {"outputPath": "work[0].highlights[1]", "decision": "ACCEPT"}
                                  ]
                                }
                                """.formatted(taskUpdatedAt.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    // ---- 2. 跨用户 ----

    @Test
    @Order(3)
    @DisplayName("POST /confirm 跨用户返回 40401")
    void postConfirm_crossUser_40401() throws Exception {
        mockMvc.perform(post("/api/ai/tasks/" + taskId + "/confirm")
                        .header("Authorization", "Bearer " + tokenB)
                        .header("Idempotency-Key", "conf-cross-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskUpdatedAt": "%s",
                                  "items": [
                                    {"outputPath": "work[0].highlights[1]", "decision": "ACCEPT"}
                                  ]
                                }
                                """.formatted(taskUpdatedAt.toString())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ---- 3. confirm 成功 ----

    @Test
    @Order(4)
    @DisplayName("POST /confirm 成功返回 ConfirmResponse + 新 versionNo")
    void postConfirm_success() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ai/tasks/" + taskId + "/confirm")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", "conf-confirm-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskUpdatedAt": "%s",
                                  "items": [
                                    {"outputPath": "work[0].highlights[1]", "decision": "ACCEPT"}
                                  ]
                                }
                                """.formatted(taskUpdatedAt.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.resumeVersionId").isNumber())
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andReturn();

        // 验证任务已 CONFIRMED
        mockMvc.perform(get("/api/ai/tasks/" + taskId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.data.confirmationStatus").value("CONFIRMED"));
    }

    // ---- 4. 幂等重放 ----

    @Test
    @Order(5)
    @DisplayName("POST /confirm 幂等键重放返回同一 versionNo")
    void postConfirm_idempotentReplay_sameVersion() throws Exception {
        // 重新读取 updatedAt（confirm 后可能变化）
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        LocalDateTime currentUpdatedAt = task.getUpdatedAt();

        mockMvc.perform(post("/api/ai/tasks/" + taskId + "/confirm")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", "conf-confirm-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskUpdatedAt": "%s",
                                  "items": [
                                    {"outputPath": "work[0].highlights[1]", "decision": "ACCEPT"}
                                  ]
                                }
                                """.formatted(currentUpdatedAt.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.versionNo").value(1));
    }

    // ---- 5. reject 成功 ----

    @Test
    @Order(6)
    @DisplayName("POST /reject 成功")
    void postReject_success() throws Exception {
        // 创建新任务用于 reject 测试
        MvcResult taskResult = mockMvc.perform(post("/api/ai/generate-resume-for-job")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", "conf-reject-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskType": "JOB_GENERATION",
                                  "targetResumeId": %d,
                                  "jobDescriptionId": %d,
                                  "input": {
                                    "includedMaterialIds": [%d],
                                    "preferredMaterialIds": [],
                                    "excludedMaterialIds": []
                                  }
                                }
                                """.formatted(resumeIdA, jdIdA, materialIdA)))
                .andExpect(status().isAccepted())
                .andReturn();
        Long rejectTaskId = objectMapper.readTree(taskResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        // 手动设为 SUCCESS + PENDING
        AiTask task = taskRepository.findById(rejectTaskId).orElseThrow();
        task.setStatus(AiTaskStatus.SUCCESS);
        task.setConfirmationStatus(ConfirmationStatus.PENDING);
        task.setResultJson(Map.of("draftResumeJson", Map.of("basics", Map.of("name", "test"))));
        taskRepository.save(task);
        task = taskRepository.findById(rejectTaskId).orElseThrow();

        mockMvc.perform(post("/api/ai/tasks/" + rejectTaskId + "/reject")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskUpdatedAt": "%s"}
                                """.formatted(task.getUpdatedAt().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 验证任务已 REJECTED
        mockMvc.perform(get("/api/ai/tasks/" + rejectTaskId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.data.confirmationStatus").value("REJECTED"));
    }
}
