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

    private String confirmationBody(LocalDateTime taskUpdatedAt) {
        return "{\"taskUpdatedAt\":\"" + taskUpdatedAt
                + "\",\"items\":[{\"outputPath\":\"basics\",\"decision\":\"ACCEPT\"}],\"additionalResumeJson\":{}}";
    }

    private record TestUser(Long id, String token) { }
}
