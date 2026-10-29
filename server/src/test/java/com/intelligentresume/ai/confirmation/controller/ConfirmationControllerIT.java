package com.intelligentresume.ai.confirmation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.domain.ConfirmationStatus;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConfirmationControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AiTaskRepository taskRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void confirmsGeneratedDraftIdempotently() throws Exception {
        TestUser user = register("confirmation_user");
        AiTask task = completedGenerationTask(user.id(), "confirm-draft-1");
        task = taskRepository.saveAndFlush(task);
        task = taskRepository.findById(task.getId()).orElseThrow();
        LocalDateTime updatedAt = task.getUpdatedAt();
        String body = confirmationBody(updatedAt);

        MvcResult first = mockMvc.perform(post("/api/ai/tasks/" + task.getId() + "/confirm")
                        .header("Authorization", "Bearer " + user.token())
                        .header("Idempotency-Key", "confirm-draft-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumeVersionId").isNumber())
                .andReturn();
        long versionId = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("resumeVersionId").asLong();

        MvcResult replay = mockMvc.perform(post("/api/ai/tasks/" + task.getId() + "/confirm")
                        .header("Authorization", "Bearer " + user.token())
                        .header("Idempotency-Key", "confirm-draft-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmationBody(taskRepository.findById(task.getId()).orElseThrow().getUpdatedAt())))
                .andExpect(status().isOk())
                .andReturn();
        long replayVersionId = objectMapper.readTree(replay.getResponse().getContentAsString())
                .path("data").path("resumeVersionId").asLong();
        assertEquals(versionId, replayVersionId);
    }

    @Test
    void rejectsCompletedDraft() throws Exception {
        TestUser user = register("reject_draft_user");
        AiTask task = taskRepository.saveAndFlush(completedGenerationTask(user.id(), "reject-draft-1"));
        task = taskRepository.findById(task.getId()).orElseThrow();

        mockMvc.perform(post("/api/ai/tasks/" + task.getId() + "/reject")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskUpdatedAt\":\"" + task.getUpdatedAt() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void rejectsStaleConfirmationWithOptimisticLockConflict() throws Exception {
        TestUser user = register("stale_confirm_user");
        AiTask task = taskRepository.saveAndFlush(completedGenerationTask(user.id(), "stale-confirm-1"));
        task = taskRepository.findById(task.getId()).orElseThrow();

        // 过期 30s 的 taskUpdatedAt → 409 + 40901（乐观锁），且不产生任何简历版本
        mockMvc.perform(post("/api/ai/tasks/" + task.getId() + "/confirm")
                        .header("Authorization", "Bearer " + user.token())
                        .header("Idempotency-Key", "stale-confirm-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmationBody(task.getUpdatedAt().minusSeconds(30))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));

        // 使用最新时间戳重新确认成功——证明拒绝原因只是乐观锁，而不是任务状态
        mockMvc.perform(post("/api/ai/tasks/" + task.getId() + "/confirm")
                        .header("Authorization", "Bearer " + user.token())
                        .header("Idempotency-Key", "stale-confirm-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmationBody(task.getUpdatedAt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.resumeVersionId").isNumber());
    }

    @Test
    void rejectsSelectionNotCoveredByItemsWith40904() throws Exception {
        // ADR-013：selected 里有 work[0] 的溯源，但 items 只提交 basics —— 客户端少提交了。
        // 期望 409 + 40904，且**不落库**：任务仍是 PENDING，随后补齐 items 的确认能正常成功。
        TestUser user = register("selection_unknown_user");
        AiTask task = taskRepository.saveAndFlush(
                completedGenerationTaskWithSelected(user.id(), "selection-unknown-1"));
        task = taskRepository.findById(task.getId()).orElseThrow();

        mockMvc.perform(post("/api/ai/tasks/" + task.getId() + "/confirm")
                        .header("Authorization", "Bearer " + user.token())
                        .header("Idempotency-Key", "selection-unknown-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmationBody(task.getUpdatedAt())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40904));

        AiTask afterReject = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals(ConfirmationStatus.PENDING, afterReject.getConfirmationStatus(),
                "40904 之后任务必须仍是 PENDING —— 半落地会让用户既不能重试也不能改选");
        assertEquals(null, afterReject.getResultResumeVersionId(), "40904 不得产生简历版本");

        // 补齐 items 后可以正常确认：证明拒绝原因只是『覆盖不全』，不是任务状态本身坏了
        mockMvc.perform(post("/api/ai/tasks/" + task.getId() + "/confirm")
                        .header("Authorization", "Bearer " + user.token())
                        .header("Idempotency-Key", "selection-unknown-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullConfirmationBody(afterReject.getUpdatedAt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.resumeVersionId").isNumber());
    }

    private TestUser register(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                + "@example.test\",\"password\":\"correcthorse\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new TestUser(userRepository.findByUsername(username).orElseThrow().getId(),
                data.path("accessToken").asText());
    }

    private AiTask completedGenerationTask(Long userId, String idempotencyKey) {
        AiTask task = new AiTask();
        task.setUserId(userId);
        task.setTaskType(AiTaskType.JOB_GENERATION);
        task.setIdempotencyKey(idempotencyKey);
        task.setRequestFingerprint(idempotencyKey);
        task.setInputSnapshotJson(Map.of("taskType", "JOB_GENERATION", "resumeTitle", "Generated resume"));
        task.setStatus(AiTaskStatus.SUCCESS);
        task.setConfirmationStatus(ConfirmationStatus.PENDING);
        task.setRetryCount(0);
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("basics", new LinkedHashMap<>(Map.of("name", "Test Candidate")));
        task.setResultJson(Map.of("draftResumeJson", draft, "selected", List.of()));
        return task;
    }

    /** 与 {@link #completedGenerationTask} 相同的草稿，但 selected 里带一条 work[0] 的溯源。 */
    private AiTask completedGenerationTaskWithSelected(Long userId, String idempotencyKey) {
        AiTask task = completedGenerationTask(userId, idempotencyKey);
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("basics", new LinkedHashMap<>(Map.of("name", "Test Candidate")));
        draft.put("work", List.of(new LinkedHashMap<>(Map.of("company", "ACME"))));
        task.setResultJson(Map.of("draftResumeJson", draft, "selected", List.of(
                Map.of("materialId", 999999, "outputPath", "work[0]", "selectedReason", "AI_REFERENCED"))));
        return task;
    }

    /** 覆盖 selected 全部溯源的 items（work[0] 以 REJECT 提交，避免依赖素材行是否存在）。 */
    private String fullConfirmationBody(LocalDateTime taskUpdatedAt) {
        return "{\"taskUpdatedAt\":\"" + taskUpdatedAt
                + "\",\"items\":[{\"outputPath\":\"basics\",\"decision\":\"ACCEPT\"},"
                + "{\"outputPath\":\"work[0]\",\"decision\":\"REJECT\"}],\"additionalResumeJson\":{}}";
    }

    private String confirmationBody(LocalDateTime taskUpdatedAt) {
        return "{\"taskUpdatedAt\":\"" + taskUpdatedAt
                + "\",\"items\":[{\"outputPath\":\"basics\",\"decision\":\"ACCEPT\"}],\"additionalResumeJson\":{}}";
    }

    private record TestUser(Long id, String token) { }
}
