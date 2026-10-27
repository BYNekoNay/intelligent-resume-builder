package com.intelligentresume.ai.generation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.generation.service.JobGenerationInputAssembler;
import com.intelligentresume.ai.preview.AiPreviewGuard;
import com.intelligentresume.ai.provider.AiMessageAssembler;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.domain.ConfirmationStatus;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.auth.repository.UserRepository;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 2 片：JOB_GENERATION 的发送前预览与令牌强制（AC-R32-8 / 9 / 10 / 12）。
 *
 * <p>四类用例与第 1 片（选材）镜像：happy path 走**真实预览端点**取令牌（不在测试里自己算哈希，
 * 否则测的就不是客户端协议），另加"缺令牌"、"令牌失效"、"预览只读"。
 *
 * <p>确认选材的前置状态（SUCCESS 的选材任务 + 候选结果）用仓储直接铺数据：worker 真跑一次 AI
 * 才能产出 SUCCESS，而这里是协议测试，不该被模型可用性绑架。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobGenerationPreviewProtocolIT {

    private static final String CONFIRM = "/api/ai/tasks/%d/confirm-materials";
    private static final String PREVIEW = "/api/ai/tasks/%d/confirm-materials/preview";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AiTaskRepository taskRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JobGenerationInputAssembler inputAssembler;

    /** 本类产生的任务行，用后即删（见 {@link #cleanUp()}）。 */
    private final List<AiTask> created = new ArrayList<>();

    /**
     * 本类会造出 ai_task 的**自引用**行（子任务 → 选材任务）。其它 IT 用 {@code deleteAll()}
     * 清理残留时按任意顺序删，会撞上 {@code FK_AI_TASK_PARENT}，因此这里自己收尾：
     * 先删子任务再删父任务，不给后续测试类留地雷。
     */
    @AfterEach
    void cleanUp() {
        // 子任务（有 parentTaskId）先删，父任务后删
        created.sort(Comparator.comparing(task -> task.getParentTaskId() == null ? 1 : 0));
        for (AiTask task : created) {
            taskRepository.delete(task);
            // 逐行立即落库：批量删除会被 Hibernate 重排序，父行先删就撞外键
            taskRepository.flush();
        }
        created.clear();
    }

    @Test
    @DisplayName("R32：先预览再确认选材 → 令牌通过，worker 组装的消息与预览逐字节相等")
    void previewThenConfirmProducesIdenticalMessages() throws Exception {
        String token = register("gen_preview_happy", "gen_preview_happy@example.com");
        grantConsent(token);
        long jobId = createJob(token);
        long materialA = createMaterial(token, "订单平台重构");
        long materialB = createMaterial(token, "Java SDK 维护");
        AiTask selection = seedSuccessfulSelection("gen_preview_happy", jobId, List.of(materialA, materialB));
        long before = taskRepository.count();

        String body = confirmBody(selection.getUpdatedAt(), List.of(materialA, materialB));
        MvcResult previewResult = preview(token, selection.getId(), body);
        List<Map<String, String>> previewed = messagesOf(previewResult);
        String previewToken = previewTokenOf(previewResult);

        MvcResult confirmed = mockMvc.perform(post(CONFIRM.formatted(selection.getId()))
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "gen-preview-happy")
                        .header(AiPreviewGuard.HEADER, previewToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.taskType").value("JOB_GENERATION"))
                .andReturn();
        long childId = objectMapper.readTree(confirmed.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        assertEquals(before + 1, taskRepository.count(), "有效令牌应恰好产生一条子任务");

        // AC-R32-8：worker 从**落库的快照**得到的消息，必须与预览返回的逐字节相等
        AiTask child = taskRepository.findById(childId).orElseThrow();
        created.add(child);
        List<Map<String, String>> workerMessages = AiMessageAssembler.assemble(AiTaskType.JOB_GENERATION,
                inputAssembler.fromSnapshot(child.getInputSnapshotJson()).providerInput());
        assertEquals(previewed, workerMessages, "预览内容必须与 worker 实际发送的内容完全一致");
        assertEquals(ConfirmationStatus.CONFIRMED,
                taskRepository.findById(selection.getId()).orElseThrow().getConfirmationStatus());
    }

    @Test
    @DisplayName("R32：不带预览令牌确认选材 → 40001，且 ai_task 零新增")
    void confirmWithoutPreviewTokenIsRejected() throws Exception {
        String token = register("gen_preview_missing", "gen_preview_missing@example.com");
        grantConsent(token);
        long jobId = createJob(token);
        long materialId = createMaterial(token, "支付系统重构");
        AiTask selection = seedSuccessfulSelection("gen_preview_missing", jobId, List.of(materialId));
        long before = taskRepository.count();

        mockMvc.perform(post(CONFIRM.formatted(selection.getId()))
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "gen-preview-missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(selection.getUpdatedAt(), List.of(materialId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
        assertEquals(before, taskRepository.count(), "未预览的请求不得产生 ai_task 行");
        assertEquals(ConfirmationStatus.PENDING,
                taskRepository.findById(selection.getId()).orElseThrow().getConfirmationStatus());
    }

    @Test
    @DisplayName("R32：预览后改动素材 → 令牌失效 40901，且 ai_task 零新增")
    void confirmWithStalePreviewTokenIsRejected() throws Exception {
        String token = register("gen_preview_stale", "gen_preview_stale@example.com");
        grantConsent(token);
        long jobId = createJob(token);
        long materialA = createMaterial(token, "订单平台重构");
        long materialB = createMaterial(token, "Java SDK 维护");
        AiTask selection = seedSuccessfulSelection("gen_preview_stale", jobId, List.of(materialA, materialB));
        long before = taskRepository.count();

        // 预览时只勾了 A，提交时改成了 A + B —— 待发内容变了，令牌必须失效
        String previewToken = previewTokenOf(preview(token, selection.getId(),
                confirmBody(selection.getUpdatedAt(), List.of(materialA))));

        mockMvc.perform(post(CONFIRM.formatted(selection.getId()))
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "gen-preview-stale")
                        .header(AiPreviewGuard.HEADER, previewToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(selection.getUpdatedAt(), List.of(materialA, materialB))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
        assertEquals(before, taskRepository.count(), "令牌不匹配的请求不得产生 ai_task 行");
    }

    @Test
    @DisplayName("R32：预览只读 —— 不落库、不改选材状态、不产生子任务")
    void previewIsReadOnly() throws Exception {
        String token = register("gen_preview_readonly", "gen_preview_readonly@example.com");
        grantConsent(token);
        long jobId = createJob(token);
        long materialId = createMaterial(token, "订单平台重构");
        AiTask selection = seedSuccessfulSelection("gen_preview_readonly", jobId, List.of(materialId));
        long before = taskRepository.count();
        LocalDateTime updatedAtBefore = selection.getUpdatedAt();

        mockMvc.perform(post(PREVIEW.formatted(selection.getId()))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(selection.getUpdatedAt(), List.of(materialId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previewToken").isNotEmpty())
                .andExpect(jsonPath("$.data.includeContact").value(false))
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.redactions").isArray());

        assertEquals(before, taskRepository.count(), "预览不能产生 ai_task 行");
        AiTask after = taskRepository.findById(selection.getId()).orElseThrow();
        assertEquals(ConfirmationStatus.PENDING, after.getConfirmationStatus(), "预览不得改动选材状态");
        assertEquals(updatedAtBefore, after.getUpdatedAt(), "预览不得触发选材任务更新");
    }

    @Test
    @DisplayName("R32：预览前置校验 —— 非本人任务 40401、选材未成功 40901")
    void previewRejectsForeignOrUnfinishedSelection() throws Exception {
        String owner = register("gen_preview_owner", "gen_preview_owner@example.com");
        grantConsent(owner);
        long jobId = createJob(owner);
        long materialId = createMaterial(owner, "订单平台重构");
        AiTask selection = seedSelection("gen_preview_owner", jobId, List.of(materialId), AiTaskStatus.SUCCESS);
        String body = confirmBody(selection.getUpdatedAt(), List.of(materialId));

        // 别人的任务：不暴露存在性（40401 与"不存在"同码）
        String stranger = register("gen_preview_stranger", "gen_preview_stranger@example.com");
        mockMvc.perform(post(PREVIEW.formatted(selection.getId()))
                        .header("Authorization", "Bearer " + stranger)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));

        // 还没跑完的选材任务：没有候选结果可确认，明确拒绝而不是给出空预览
        AiTask unfinished = seedSelection("gen_preview_owner", jobId, List.of(materialId), AiTaskStatus.PENDING);
        mockMvc.perform(post(PREVIEW.formatted(unfinished.getId()))
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody(unfinished.getUpdatedAt(), List.of(materialId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
    }

    // ------------------------------------------------------------------ 辅助

    private String register(String username, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"email\":\"%s\",\"password\":\"correcthorse\"}"
                                .formatted(username, email)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    private void grantConsent(String token) throws Exception {
        mockMvc.perform(post("/api/ai/consent")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"policyVersion":"v1.2.0","providerCode":"bailian",
                                 "taskScopes":["JOB_MATERIAL_SELECTION","JOB_GENERATION"],
                                 "dataCategories":["JOB_DESCRIPTION","CAREER_MATERIAL","PERSONAL_PROFILE"],
                                 "noticeHash":"test"}
                                """))
                .andExpect(status().isCreated());
    }

    private long createJob(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Java Backend Engineer\",\"companyName\":\"Example\","
                                + "\"jdText\":\"Build Java Spring Boot backend services with MySQL and Redis.\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private long createMaterial(String token, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/career-materials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"materialType\":\"WORK_EXPERIENCE\",\"title\":\"%s\","
                                + "\"sourceText\":\"Built Spring Boot services using MySQL and Redis.\","
                                + "\"contentJson\":{\"company\":\"Example\"},\"usagePreference\":\"NORMAL\"}")
                                .formatted(title)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    /** 铺一条已完成（SUCCESS）的选材任务：候选 = 传入素材，均被推荐。 */
    private AiTask seedSuccessfulSelection(String username, long jobId, List<Long> candidateIds) {
        return seedSelection(username, jobId, candidateIds, AiTaskStatus.SUCCESS);
    }

    private AiTask seedSelection(String username, long jobId, List<Long> candidateIds, AiTaskStatus status) {
        Long userId = userRepository.findByUsername(username).orElseThrow().getId();
        List<Map<String, Object>> recommended = candidateIds.stream()
                .map(id -> Map.<String, Object>of("materialId", id, "reason", "直接匹配岗位要求"))
                .toList();
        AiTask selection = new AiTask();
        selection.setUserId(userId);
        selection.setTaskType(AiTaskType.JOB_MATERIAL_SELECTION);
        // 同一用户可能铺多条（SUCCESS 与未完成的），幂等键必须互不相同
        String key = "selection-" + username + "-" + status;
        selection.setIdempotencyKey(key);
        selection.setRequestFingerprint(key);
        selection.setInputSnapshotJson(Map.of(
                "taskType", AiTaskType.JOB_MATERIAL_SELECTION.name(),
                "jobDescriptionId", jobId,
                "resumeTitle", "Java 后端 - Example"));
        selection.setStatus(status);
        selection.setConfirmationStatus(ConfirmationStatus.PENDING);
        selection.setRetryCount(0);
        selection.setResultJson(Map.of(
                "recommended", recommended,
                "unselected", List.of(),
                "excluded", List.of(),
                "missingRequirements", List.of("大型团队管理经验")));
        AiTask saved = taskRepository.saveAndFlush(selection);
        // 读回落库后的时间戳：confirm 用它做乐观锁比对，测试必须拿到与数据库一致的精度
        AiTask reloaded = taskRepository.findById(saved.getId()).orElseThrow();
        created.add(reloaded);
        return reloaded;
    }

    private String confirmBody(LocalDateTime taskUpdatedAt, List<Long> selectedMaterialIds) {
        return """
                {"taskUpdatedAt":"%s","selectedMaterialIds":%s,"forcedIncludedMaterialIds":[],"resumeTitle":"Java 后端 - Example"}
                """.formatted(taskUpdatedAt, selectedMaterialIds);
    }

    private MvcResult preview(String token, long selectionId, String body) throws Exception {
        return mockMvc.perform(post(PREVIEW.formatted(selectionId))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
    }

    /** 用字节流解析：预览内容含中文，按字符集猜会得到乱码，比对就变成假失败。 */
    private List<Map<String, String>> messagesOf(MvcResult result) throws Exception {
        JsonNode messages = objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("data").path("messages");
        List<Map<String, String>> parsed = new ArrayList<>();
        for (JsonNode message : messages) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("role", message.path("role").asText());
            entry.put("content", message.path("content").asText());
            parsed.add(entry);
        }
        return parsed;
    }

    private String previewTokenOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("previewToken").asText();
    }
}
