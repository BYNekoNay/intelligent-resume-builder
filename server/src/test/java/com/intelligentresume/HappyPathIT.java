package com.intelligentresume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.confirmation.service.ConfirmationService;
import com.intelligentresume.ai.confirmation.dto.ConfirmRequest;
import com.intelligentresume.ai.confirmation.dto.ConfirmRequest.ConfirmedDraftItem;
import com.intelligentresume.ai.confirmation.dto.ConfirmRequest.Decision;
import com.intelligentresume.ai.generation.service.JobGenerationService;
import com.intelligentresume.ai.worker.DatabaseTaskWorker;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.auth.domain.User;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.auth.service.AuthService;
import com.intelligentresume.auth.dto.LoginRequest;
import com.intelligentresume.auth.dto.RegisterRequest;
import com.intelligentresume.auth.dto.TokenResponse;
import com.intelligentresume.auth.jwt.TokenService;
import com.intelligentresume.careermaterial.dto.CareerMaterialCreateRequest;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.careermaterial.service.CareerMaterialService;
import com.intelligentresume.jobdescription.dto.JobDescriptionCreateRequest;
import com.intelligentresume.jobdescription.service.JobDescriptionService;
import com.intelligentresume.resume.dto.ResumeCreateRequest;
import com.intelligentresume.resume.dto.ResumeVersionCreateRequest;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import com.intelligentresume.resume.repository.ResumeMaterialReferenceRepository;
import com.intelligentresume.resume.service.ResumeService;
import com.intelligentresume.resume.domain.ResumeVersion.SourceType;
import com.intelligentresume.scoring.dto.MatchRequest;
import com.intelligentresume.scoring.dto.MatchResponse;
import com.intelligentresume.scoring.service.ScoringService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVP 端到端 happy path 集成测试。
 *
 * <p>用 H2 + Flyway 验证以下链路:
 * <ol>
 *     <li>注册 → 登录 → 拿到 access token</li>
 *     <li>建立 JD → 解析关键词</li>
 *     <li>建简历 → 建版本</li>
 *     <li>加 1 条 SKILL 资料(usage_preference = PREFERRED)</li>
 *     <li>AI 同意 → 创建任务 → 手动驱动 worker 同步跑生成</li>
 *     <li>逐项 ACCEPT 确认 → 新简历版本落地</li>
 *     <li>跑规则覆盖度评分 → 拿到含 disclaimer 的结果</li>
 * </ol>
 *
 * PDF 导出因依赖外部 pdf-service,这里只做"任务创建"成功即可,不发起真正的 HTTP 调用。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HappyPathIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuthService authService;
    @Autowired JobDescriptionService jobDescriptionService;
    @Autowired ResumeService resumeService;
    @Autowired CareerMaterialService careerMaterialService;
    @Autowired UserRepository userRepository;
    @Autowired AiTaskRepository aiTaskRepository;
    @Autowired JobGenerationService jobGenerationService;
    @Autowired DatabaseTaskWorker databaseTaskWorker;
    @Autowired ConfirmationService confirmationService;
    @Autowired ResumeVersionRepository resumeVersionRepository;
    @Autowired ResumeMaterialReferenceRepository resumeMaterialReferenceRepository;
    @Autowired TokenService tokenService;
    @Autowired ScoringService scoringService;

    @Test
    @DisplayName("MVP 端到端 hello path")
    void happyPath() throws Exception {
        // 0. 注册并登录
        String username = "user_" + UUID.randomUUID().toString().substring(0, 8);
        TokenResponse tokens = authService.register(new RegisterRequest(
                username, username + "@example.com", "StrongPassword!1"));
        TokenResponse loginAgain = authService.login(new LoginRequest(username, "StrongPassword!1"));
        assertThat(loginAgain.accessToken()).isNotBlank();
        assertThat(tokenService.parseUserId(loginAgain.accessToken())).isNotNull();

        User user = userRepository.findByUsername(username).orElseThrow();
        Long userId = user.getId();

        // 1. 建 JD + 解析
        var jd = jobDescriptionService.create(new JobDescriptionCreateRequest(
                "后端工程师",
                "Acme",
                "我们寻找 3 年以上 Spring Boot 经验,熟悉 MySQL,Redis,参与大型项目。"),
                userId);
        var jdParsed = jobDescriptionService.parse(jd.id(), userId);
        assertThat(jdParsed.parsedKeywordsJson()).isNotNull();

        // 2. 建简历 + 加初始版本
        var resume = resumeService.create(new ResumeCreateRequest(
                "我的简历", Map.of("basics", Map.of("name", username))), userId);
        var initialVersion = resumeService.createVersion(resume.id(),
                new ResumeVersionCreateRequest(
                        Map.of(
                                "basics", Map.of("name", username, "summary", "experienced backend dev"),
                                "skills", List.of(Map.of("name", "Spring Boot")),
                                "work", List.of(Map.of("company", "OldCorp", "position", "Engineer"))
                        ),
                        SourceType.MANUAL,
                        null),
                userId);

        // 3. 加一条 PREFERRED 资料
        var material = careerMaterialService.create(new CareerMaterialCreateRequest(
                MaterialType.SKILL, "Spring Boot",
                Map.of("proficiency", 4), "经验: 用 Spring Boot 超过 3 年。",
                UsagePreference.PREFERRED), userId);

        // 4. AI 同意 + 创建任务
        String consentJson = mockMvc.perform(post("/api/ai/consent")
                        .header("Authorization", "Bearer " + loginAgain.accessToken())
                        .contentType("application/json")
                        .content("""
                                {"policyVersion":"v1","providerCode":"mock",
                                 "taskScopes":["JOB_GENERATION"],
                                 "dataCategories":["career_material"],
                                 "noticeHash":"sha256:test"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(consentJson).contains("GRANTED");

        String taskJson = mockMvc.perform(post("/api/ai/generate-resume-for-job")
                        .header("Authorization", "Bearer " + loginAgain.accessToken())
                        .header("Idempotency-Key", "test-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetResumeId", resume.id(),
                                "jobDescriptionId", jd.id(),
                                "preferredMaterialIds", List.of(material.id())))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long taskId = objectMapper.readTree(taskJson).path("data").path("taskId").asLong();

        // 5. 按真实工作器路径领取后执行生成
        assertThat(databaseTaskWorker.claim(taskId)).isTrue();
        jobGenerationService.run(taskId);
        AiTask task = aiTaskRepository.findById(taskId).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(AiTask.TaskStatus.SUCCESS);
        assertThat(task.getResultJson()).isNotNull();
        assertThat(objectMapper.writeValueAsString(task.getResultJson()))
                .contains("material:" + material.id())
                .contains("Spring Boot")
                .doesNotContain("MockCorp", "Mock Project");
        assertThat((List<?>) task.getResultJson().get("selected")).isNotEmpty();

        // 6. 确认:逐项 ACCEPT(草稿有 _pending=true 的我们 REJECT,其他 ACCEPT)
        // Hibernate JSON 列反序列化为 LinkedHashMap,这里手动转换。
        Object rawDraftJson = task.getResultJson().get("draftResumeJson");
        JsonNode draftJson = objectMapper.valueToTree(rawDraftJson);
        List<ConfirmedDraftItem> items = new java.util.ArrayList<>();
        Iterator<String> fields = draftJson.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            JsonNode value = draftJson.get(field);
            if (value.isArray()) {
                for (int i = 0; i < value.size(); i++) {
                    JsonNode entry = value.get(i);
                    boolean pending = entry.path("_pending").asBoolean(false);
                    items.add(new ConfirmedDraftItem("/" + field + "/" + i,
                            pending ? Decision.REJECT : Decision.ACCEPT, null));
                }
            } else {
                items.add(new ConfirmedDraftItem("/" + field, Decision.ACCEPT, null));
            }
        }
        collectNestedPendingDecisions(draftJson, "", items);
        java.time.LocalDateTime taskUpdatedAt = task.getUpdatedAt();
        var confirmResponse = confirmationService.confirm(taskId,
                new ConfirmRequest(taskUpdatedAt, items, null),
                "idem-" + UUID.randomUUID(),
                userId);
        assertThat(confirmResponse.resumeVersionId()).isNotNull();
        assertThat(resumeMaterialReferenceRepository
                .findByMaterialIdOrderByCreatedAtDesc(material.id()))
                .anyMatch(reference -> reference.getResumeVersionId().equals(confirmResponse.resumeVersionId())
                        && reference.getSelectionStatus().equals("ACCEPT"));

        // 7. 评分:用最新版本 + 同一 JD
        Long newResumeVersionId = confirmResponse.resumeVersionId();
        ResumeVersion created = resumeVersionRepository.findById(newResumeVersionId).orElseThrow();
        assertThat(created.getVersionNo()).isPositive();

        MatchResponse match = scoringService.score(
                new MatchRequest(newResumeVersionId, jd.id()), userId);
        assertThat(match.totalScore()).isNotNull();
        assertThat(match.explanation().disclaimer())
                .contains("非企业 ATS 结果")
                .contains("非录用概率");
    }

    private void collectNestedPendingDecisions(JsonNode node, String path, List<ConfirmedDraftItem> items) {
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                collectNestedPendingDecisions(node.get(index), path + "/" + index, items);
            }
            return;
        }
        if (!node.isObject()) return;
        if (node.has("_pending") && !path.isBlank()
                && items.stream().noneMatch(item -> item.outputPath().equals(path))) {
            items.add(new ConfirmedDraftItem(path, Decision.REJECT, null));
        }
        node.fields().forEachRemaining(entry -> {
            if (!entry.getKey().startsWith("_")) {
                String segment = entry.getKey().replace("~", "~0").replace("/", "~1");
                collectNestedPendingDecisions(entry.getValue(), path + "/" + segment, items);
            }
        });
    }
}
