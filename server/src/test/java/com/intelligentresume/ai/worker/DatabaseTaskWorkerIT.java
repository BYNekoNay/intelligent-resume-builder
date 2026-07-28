package com.intelligentresume.ai.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * DatabaseTaskWorker 集成测试（H2 + 百炼提供者）。
 * 覆盖:领取并执行、过期租约恢复，以及未配置密钥时的失败状态。
 *
 * <p>测试环境关闭自动调度,通过直接调用 {@code worker.poll()} 手动触发。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseTaskWorkerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DatabaseTaskWorker worker;
    @Autowired private AiTaskRepository taskRepository;

    private static String token;

    private String registerAndGetToken(String username, String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s","password":"%s"}
                                """.formatted(username, email, password)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("data").get("accessToken").asText();
    }

    private Long createTask(String taskType) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/ai/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskType": "%s", "input": {"prompt": "test"}}
                                """.formatted(taskType)))
                .andExpect(status().isAccepted())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    @Test
    @Order(1)
    @DisplayName("准备: 清理残留任务 + 注册用户并授权 AI 同意")
    void setup() throws Exception {
        // 清理其他 IT 类（如 AiTaskControllerIT）残留的 PENDING 任务,
        // 避免 batch-size=1 时工作器领取到旧任务
        taskRepository.deleteAll();

        token = registerAndGetToken("worker_user", "worker_user@example.com", "correcthorse");
        mockMvc.perform(post("/api/ai/consent")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "policyVersion": "v1.1.0",
                                  "providerCode": "bailian",
                                  "taskScopes": ["MATERIAL_IMPORT", "RESUME_OPTIMIZE"],
                                  "dataCategories": ["resume"],
                                  "noticeHash": "hash"
                                }
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    @Order(2)
    @DisplayName("工作器领取 PENDING 任务，未配置密钥时标记为 FAILED")
    void workerPoll_claimsAndExecutesToFailedWithoutApiKey() throws Exception {
        Long taskId = createTask("MATERIAL_IMPORT");

        // 手动触发工作器轮询
        worker.poll();

        AiTask task = taskRepository.findById(taskId).orElseThrow();
        assertEquals(AiTaskStatus.FAILED, task.getStatus());
        assertTrue(task.getErrorMessage().contains("API Key"));
        assertNull(task.getLeaseOwner(), "成功后应清除租约");
        assertNull(task.getLeaseExpiresAt());
    }

    @Test
    @Order(3)
    @DisplayName("过期租约的 RUNNING 任务被重新领取，未配置密钥时标记为 FAILED")
    void workerPoll_recoversExpiredLease() throws Exception {
        Long taskId = createTask("RESUME_OPTIMIZE");

        // 模拟过期租约:直接修改任务状态为 RUNNING + 过期时间
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        task.setStatus(AiTaskStatus.RUNNING);
        task.setLeaseOwner("dead-worker");
        // Use an unambiguously expired timestamp so H2's NOW() implementation and
        // the JVM/database clock cannot make this recovery-path test flaky.
        task.setLeaseExpiresAt(LocalDateTime.of(2000, 1, 1, 0, 0));
        task.setRetryCount(1);
        taskRepository.saveAndFlush(task);

        // 工作器应能重新领取过期任务
        worker.poll();

        AiTask updated = taskRepository.findById(taskId).orElseThrow();
        assertEquals(AiTaskStatus.FAILED, updated.getStatus());
        assertTrue(updated.getErrorMessage().contains("API Key"));
        assertNull(updated.getLeaseOwner());
    }
}
