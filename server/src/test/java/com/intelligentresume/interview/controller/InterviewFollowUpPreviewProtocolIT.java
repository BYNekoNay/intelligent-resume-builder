package com.intelligentresume.interview.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.preview.AiPreviewGuard;
import com.intelligentresume.ai.provider.AiMessageAssembler;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.interview.domain.InterviewMode;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewSourceType;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import com.intelligentresume.interview.service.InterviewFollowUpPromptAssembler;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 5 片：INTERVIEW_COACH 追问的发送前预览与令牌强制（AC-R32-8 / 9 / 10 / 12）。
 *
 * <p>会话用仓储直接铺成 COMPLETED：走一遍真实面试才能到该状态，而这里是协议测试，
 * 不该被面试流程与模型可用性绑架。happy path 走**真实预览端点**取令牌，
 * 不在测试里自己算哈希。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InterviewFollowUpPreviewProtocolIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private InterviewSessionRepository sessionRepository;
    @Autowired private AiTaskRepository taskRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private InterviewFollowUpPromptAssembler promptAssembler;

    private final List<AiTask> createdTasks = new ArrayList<>();
    private final List<InterviewSession> createdSessions = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdTasks.forEach(taskRepository::delete);
        createdSessions.forEach(sessionRepository::delete);
        createdTasks.clear();
        createdSessions.clear();
    }

    @Test
    @DisplayName("R32：先预览再发起追问 → 202，且与 worker 从落库快照组装的消息逐字节相等")
    void previewThenCreateProducesIdenticalMessages() throws Exception {
        String token = register("iv_preview_happy");
        grantConsent(token);
        InterviewSession session = seedCompletedSession(userId("iv_preview_happy"));
        long before = taskRepository.count();

        MvcResult previewed = preview(token, session.getId(), "缺少量化成果");
        List<Map<String, String>> expected = messagesOf(previewed);
        String previewToken = previewTokenOf(previewed);

        MvcResult created = mockMvc.perform(post("/api/interviews/" + session.getId() + "/follow-up")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "iv-preview-happy")
                        .header(AiPreviewGuard.HEADER, previewToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weakness\":\"缺少量化成果\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.taskType").value("INTERVIEW_COACH"))
                .andReturn();
        assertEquals(before + 1, taskRepository.count(), "有效令牌应恰好产生一条任务");

        long taskId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        AiTask task = taskRepository.findById(taskId).orElseThrow();
        createdTasks.add(task);
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) task.getInputSnapshotJson().get("input");
        List<Map<String, String>> actual = AiMessageAssembler.assemble(AiTaskType.INTERVIEW_COACH,
                promptAssembler.buildProviderInput(session, input.get("weakness").toString(), task.getUserId()));
        assertEquals(expected, actual, "预览内容必须与 worker 实际发送的内容完全一致");
    }

    @Test
    @DisplayName("R32：不带预览令牌发起追问 → 40001，且 ai_task 零新增")
    void createWithoutPreviewTokenIsRejected() throws Exception {
        String token = register("iv_preview_missing");
        grantConsent(token);
        InterviewSession session = seedCompletedSession(userId("iv_preview_missing"));
        long before = taskRepository.count();

        mockMvc.perform(post("/api/interviews/" + session.getId() + "/follow-up")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "iv-preview-missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weakness\":\"缺少量化成果\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
        assertEquals(before, taskRepository.count(), "缺令牌不得产生 ai_task 行");
    }

    @Test
    @DisplayName("R32：预览后改了薄弱项 → 40901，且 ai_task 零新增")
    void createWithStalePreviewTokenIsRejected() throws Exception {
        String token = register("iv_preview_stale");
        grantConsent(token);
        InterviewSession session = seedCompletedSession(userId("iv_preview_stale"));
        String staleToken = previewTokenOf(preview(token, session.getId(), "缺少量化成果"));
        long before = taskRepository.count();

        mockMvc.perform(post("/api/interviews/" + session.getId() + "/follow-up")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "iv-preview-stale")
                        .header(AiPreviewGuard.HEADER, staleToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weakness\":\"换成了另一个薄弱项\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
        assertEquals(before, taskRepository.count(), "令牌失效不得产生 ai_task 行");
    }

    @Test
    @DisplayName("R32：预览只读（不落库），未完成的会话一律 40901")
    void previewIsReadOnlyAndRequiresCompletedSession() throws Exception {
        String token = register("iv_preview_readonly");
        InterviewSession session = seedCompletedSession(userId("iv_preview_readonly"));
        long before = taskRepository.count();

        preview(token, session.getId(), "缺少量化成果");
        assertEquals(before, taskRepository.count(), "预览不得写库（AC-R32-12）");

        InterviewSession running = seedSession(userId("iv_preview_readonly"), InterviewStatus.AWAITING_ANSWER);
        mockMvc.perform(post("/api/interviews/" + running.getId() + "/follow-up/preview")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weakness\":\"缺少量化成果\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
    }

    @Test
    @DisplayName("追问创建幂等：同键重放返回同一任务（重放不需再带令牌），新键创建新任务")
    void idempotentReplayReturnsSameTask() throws Exception {
        String token = register("iv_followup_idem");
        grantConsent(token);
        Long userId = userId("iv_followup_idem");
        InterviewSession session = seedCompletedSession(userId);
        String body = "{\"weakness\":\"缓存与并发控制经验不足\"}";
        String previewToken = previewTokenOf(preview(token, session.getId(), "缓存与并发控制经验不足"));

        // 会话未完成时 follow-up 必须被拒（409），且该拒绝不消费幂等键
        InterviewSession running = seedSession(userId, InterviewStatus.AWAITING_ANSWER);
        createFollowUp(token, running.getId(), "iv-idem-unfinished", null, body)
                .andExpect(status().isConflict());

        MvcResult first = createFollowUp(token, session.getId(), "iv-idem-key", previewToken, body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();
        long firstTaskId = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        createdTasks.add(taskRepository.findById(firstTaskId).orElseThrow());

        // 同键重放**不需要**再带令牌：守卫排在重放判定之后，否则客户端重试会被 40001 打死
        createFollowUp(token, session.getId(), "iv-idem-key", null, body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(firstTaskId));

        // 新键必须创建新任务，不得复用旧键的任务
        MvcResult fresh = createFollowUp(token, session.getId(), "iv-idem-key-2", previewToken, body)
                .andExpect(status().isAccepted())
                .andReturn();
        long freshTaskId = objectMapper.readTree(fresh.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        createdTasks.add(taskRepository.findById(freshTaskId).orElseThrow());
        org.junit.jupiter.api.Assertions.assertNotEquals(firstTaskId, freshTaskId,
                "a fresh idempotency key must create a new follow-up task");
    }

    private org.springframework.test.web.servlet.ResultActions createFollowUp(
            String token, Long sessionId, String key, String previewToken, String body) throws Exception {
        var builder = post("/api/interviews/" + sessionId + "/follow-up")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (previewToken != null) {
            builder = builder.header(AiPreviewGuard.HEADER, previewToken);
        }
        return mockMvc.perform(builder);
    }

    private MvcResult preview(String token, Long sessionId, String weakness) throws Exception {
        return mockMvc.perform(post("/api/interviews/" + sessionId + "/follow-up/preview")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weakness\":\"" + weakness + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previewToken").isNotEmpty())
                .andExpect(jsonPath("$.data.messages").isArray())
                .andReturn();
    }

    private List<Map<String, String>> messagesOf(MvcResult result) throws Exception {
        // 必须显式 UTF-8：getContentAsString() 默认按 ISO-8859-1 解码，中文 prompt 会变乱码，
        // 进而让"预览 == 实际发送"的断言误报（乱码的是读取侧，不是服务端内容）。
        return objectMapper.convertValue(objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("messages"), new TypeReference<>() { });
    }

    private String previewTokenOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("previewToken").asText();
    }

    private InterviewSession seedCompletedSession(Long userId) {
        return seedSession(userId, InterviewStatus.COMPLETED);
    }

    private InterviewSession seedSession(Long userId, InterviewStatus status) {
        InterviewSession session = new InterviewSession();
        session.setUserId(userId);
        session.setSourceType(InterviewSourceType.PLATFORM_RESUME);
        session.setInterviewMode(InterviewMode.TECHNICAL);
        session.setStatus(status);
        InterviewSession saved = sessionRepository.saveAndFlush(session);
        createdSessions.add(saved);
        return saved;
    }

    private Long userId(String username) {
        return userRepository.findByUsername(username).orElseThrow().getId();
    }

    private void grantConsent(String token) throws Exception {
        mockMvc.perform(post("/api/ai/consent").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "policyVersion":"v1.2.0",
                                  "providerCode":"bailian",
                                  "taskScopes":["INTERVIEW_COACH"],
                                  "dataCategories":["RESUME","INTERVIEW_ANSWER"],
                                  "noticeHash":"interview-preview-test"
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
