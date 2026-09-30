package com.intelligentresume.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.consent.domain.AiConsent;
import com.intelligentresume.ai.consent.domain.ConsentStatus;
import com.intelligentresume.ai.consent.repository.AiConsentRepository;
import com.intelligentresume.application.domain.ApplicationRecord;
import com.intelligentresume.application.domain.ApplicationStatus;
import com.intelligentresume.application.repository.ApplicationRecordRepository;
import com.intelligentresume.auth.repository.UserRepository;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.communication.domain.CommunicationDraft;
import com.intelligentresume.communication.domain.CommunicationTemplate;
import com.intelligentresume.communication.domain.CommunicationType;
import com.intelligentresume.communication.domain.TemplateScene;
import com.intelligentresume.communication.repository.CommunicationDraftRepository;
import com.intelligentresume.communication.repository.CommunicationTemplateRepository;
import com.intelligentresume.interview.asset.domain.InterviewAnswerAsset;
import com.intelligentresume.interview.asset.domain.InterviewAssetSection;
import com.intelligentresume.interview.asset.repository.InterviewAnswerAssetRepository;
import com.intelligentresume.interview.asset.repository.InterviewAssetSectionRepository;
import com.intelligentresume.interview.domain.InterviewMode;
import com.intelligentresume.interview.domain.InterviewRecord;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewSourceType;
import com.intelligentresume.interview.domain.InterviewStatus;
import com.intelligentresume.interview.repository.InterviewRecordRepository;
import com.intelligentresume.interview.repository.InterviewSessionRepository;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.personalprofile.domain.PersonalProfile;
import com.intelligentresume.personalprofile.repository.PersonalProfileRepository;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.domain.ResumeSourceType;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.repository.ResumeRepository;
import com.intelligentresume.resume.repository.ResumeVersionRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 认证控制器集成测试（MockMvc + H2 + Flyway）。
 *
 * <p>覆盖 T02 §9 中 7 个集成测试场景。
 * 限流测试由 {@link AuthRateLimitIT} 单独覆盖（避免高限流阈值干扰）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ResumeRepository resumeRepository;
    @Autowired private ResumeVersionRepository resumeVersionRepository;
    @Autowired private CareerMaterialRepository careerMaterialRepository;
    @Autowired private JobDescriptionRepository jobDescriptionRepository;
    @Autowired private ApplicationRecordRepository applicationRecordRepository;
    @Autowired private InterviewSessionRepository interviewSessionRepository;
    @Autowired private InterviewRecordRepository interviewRecordRepository;
    @Autowired private InterviewAnswerAssetRepository interviewAnswerAssetRepository;
    @Autowired private InterviewAssetSectionRepository interviewAssetSectionRepository;
    @Autowired private CommunicationTemplateRepository communicationTemplateRepository;
    @Autowired private CommunicationDraftRepository communicationDraftRepository;
    @Autowired private PersonalProfileRepository personalProfileRepository;
    @Autowired private AiConsentRepository aiConsentRepository;

    private static final String REGISTER_BODY =
            """
            {"username":"ituser","email":"ituser@example.com","password":"correcthorse"}
            """;

    private static final String LOGIN_BODY =
            """
            {"username":"ituser","password":"correcthorse"}
            """;

    // ---- 注册 ----

    @Test
    @Order(1)
    @DisplayName("POST /api/auth/register 成功")
    void postRegister_201() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTER_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.accessTokenExpiresInSeconds").isNumber())
                .andReturn();

        // refreshToken 被 @JsonIgnore，不出现在 JSON body 中
        String json = result.getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(json);
        assertTrue(node.get("data").get("refreshToken") == null
                        || node.get("data").get("refreshToken").isNull(),
                "refreshToken 不应出现在 JSON body 中");

        // Set-Cookie 应包含 refresh token
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie, "注册应设置 refresh cookie");
        assertTrue(setCookie.contains("irt_refresh="), "cookie 名应为 irt_refresh");
    }

    // ---- 登录 ----

    @Test
    @Order(2)
    @DisplayName("POST /api/auth/login 成功并 Set-Cookie 含 HttpOnly + SameSite=Lax")
    void postLogin_setsCookieAttributes() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie, "登录应设置 refresh cookie");
        assertTrue(setCookie.contains("HttpOnly"), "cookie 应含 HttpOnly");
        assertTrue(setCookie.contains("SameSite=Lax"), "cookie 应含 SameSite=Lax");
        assertTrue(setCookie.contains("irt_refresh="), "cookie 名应为 irt_refresh");
    }

    // ---- 刷新 ----

    @Test
    @Order(3)
    @DisplayName("POST /api/auth/refresh 成功且返回新 Set-Cookie")
    void postRefresh_rotatesCookie() throws Exception {
        // 先登录获取 refresh cookie
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andReturn();

        Cookie refreshCookie = loginResult.getResponse().getCookie("irt_refresh");
        assertNotNull(refreshCookie, "登录后应有 refresh cookie");
        String oldToken = refreshCookie.getValue();

        // 用 cookie 刷新
        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        Cookie newCookie = refreshResult.getResponse().getCookie("irt_refresh");
        assertNotNull(newCookie, "刷新后应有新 refresh cookie");
        assertNotEquals(oldToken, newCookie.getValue(), "刷新后 cookie 值应改变");

        // 旧 token 复用应返回 401
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("irt_refresh", oldToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    // ---- 退出 ----

    @Test
    @Order(4)
    @DisplayName("POST /api/auth/logout 成功")
    void postLogout_revokes() throws Exception {
        // 先登录
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andReturn();

        Cookie refreshCookie = loginResult.getResponse().getCookie("irt_refresh");
        assertNotNull(refreshCookie);

        // 退出
        mockMvc.perform(post("/api/auth/logout")
                        .cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 退出后旧 token 不可用
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(refreshCookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    // ---- 当前用户 ----

    @Test
    @Order(5)
    @DisplayName("GET /api/auth/me 未登录返回 40101")
    void getMe_unauthenticated_returns40101() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @Order(6)
    @DisplayName("GET /api/auth/me 已登录返回 UserInfo")
    void getMe_authenticated_returnsUserInfo() throws Exception {
        // 先登录获取 access token
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andReturn();

        String json = loginResult.getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(json)
                .get("data").get("accessToken").asText();

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("ituser"))
                .andExpect(jsonPath("$.data.email").value("ituser@example.com"));
    }

    @Test
    @Order(7)
    @DisplayName("DELETE /api/auth/me 后已签发 access token 立即失效")
    void deleteAccount_invalidatesExistingAccessToken() throws Exception {
        MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"delete_me\",\"email\":\"delete_me@example.com\",\"password\":\"correcthorse\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = objectMapper.readTree(registerResult.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();

        mockMvc.perform(delete("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    // ---- 注册校验 ----

    @Test
    @Order(8)
    @DisplayName("POST /api/auth/register 密码过短返回 40001")
    void postRegister_shortPassword_returns40001() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob","email":"bob@example.com","password":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    // ---- 账号数据导出（ideation #7） ----

    @Test
    @Order(9)
    @DisplayName("GET /api/auth/export 未登录返回 40101")
    void exportData_unauthenticated_returns40101() throws Exception {
        mockMvc.perform(get("/api/auth/export"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @Order(10)
    @DisplayName("GET /api/auth/export 聚合本人各域数据，排除他人数据/系统模板/凭据字段")
    void exportData_aggregatesOwnDataOnly() throws Exception {
        String tokenA = registerAndGetToken("export_a", "export_a@example.com");
        registerAndGetToken("export_b", "export_b@example.com");
        Long userAId = userRepository.findByUsername("export_a").orElseThrow().getId();
        Long userBId = userRepository.findByUsername("export_b").orElseThrow().getId();

        seedExportData(userAId, "userA");
        seedExportData(userBId, "userB");
        seedSystemTemplate();

        String body = mockMvc.perform(get("/api/auth/export")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode root = objectMapper.readTree(body);
        // 导出物是数据文档（非统一 ApiResponse 信封），带结构版本与导出时间
        assertEquals(1, root.path("formatVersion").asInt());
        assertTrue(root.path("exportedAt").isTextual());
        assertTrue(root.path("code").isMissingNode());
        assertEquals("export_a", root.path("account").path("username").asText());

        // 简历 + 版本（活动与归档均导出，resumeJson 为嵌套对象）
        assertEquals(1, root.path("resumes").size());
        JsonNode resumeNode = root.path("resumes").get(0);
        assertEquals("导出简历 userA", resumeNode.path("title").asText());
        assertEquals(2, resumeNode.path("versions").size());
        JsonNode activeVersion = resumeNode.path("versions").get(0);
        assertEquals("导出简历 userA", activeVersion.path("resumeJson").path("basics").path("name").asText());
        assertTrue(resumeNode.path("versions").get(1).path("deletedAt").isTextual(), "归档版本应出现在导出中");

        // 职业资料（contentJson 为嵌套对象而非转义字符串）
        assertEquals(1, root.path("careerMaterials").size());
        assertEquals("导出资料 userA", root.path("careerMaterials").get(0).path("title").asText());
        assertTrue(root.path("careerMaterials").get(0).path("contentJson").isObject());

        // JD / 投递
        assertEquals(1, root.path("jobDescriptions").size());
        assertEquals(1, root.path("applications").size());
        assertEquals("导出求职信 userA", root.path("applications").get(0).path("coverLetterText").asText());

        // 面试会话 → 轮次记录；答案资产 → 关联章节
        assertEquals(1, root.path("interviewSessions").size());
        assertEquals(1, root.path("interviewSessions").get(0).path("records").size());
        assertEquals(1, root.path("interviewAnswerAssets").size());
        assertEquals(1, root.path("interviewAnswerAssets").get(0).path("sections").size());

        // 个人资料 / 沟通模板 / 草稿 / AI 同意记录
        assertEquals("导出用户 userA", root.path("personalProfile").path("fullName").asText());
        assertEquals(1, root.path("communicationTemplates").size());
        assertEquals(1, root.path("communicationDrafts").size());
        assertEquals(1, root.path("aiConsents").size());

        // 跨用户隔离 / 系统数据排除 / 凭据与内部字段排除
        assertFalse(body.contains("userB"), "不应出现另一用户的数据");
        assertFalse(body.contains("系统模板不应导出"), "系统模板（user_id 为空）不属于用户数据");
        assertFalse(body.contains("passwordHash"), "不得导出凭据字段");
        assertFalse(body.contains("contentJsonText"), "不得导出搜索用内部投影字段");
        assertFalse(body.contains("inputSnapshotJson"), "不得导出 AI 任务快照（#7 口径明确排除）");
    }

    private String registerAndGetToken(String username, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s","password":"correcthorse"}
                                """.formatted(username, email)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    /** #7：为指定用户造各业务域最小数据；marker（userA / userB）用于跨用户隔离断言。 */
    private void seedExportData(Long userId, String marker) {
        Resume resume = new Resume();
        resume.setUserId(userId);
        resume.setTitle("导出简历 " + marker);
        resumeRepository.save(resume);

        ResumeVersion active = new ResumeVersion();
        active.setResumeId(resume.getId());
        active.setVersionNo(1);
        active.setSourceType(ResumeSourceType.MANUAL);
        active.setResumeJson(Map.of("basics", Map.of("name", "导出简历 " + marker), "work", List.of()));
        active.setCreatedBy(userId);
        resumeVersionRepository.save(active);

        ResumeVersion archived = new ResumeVersion();
        archived.setResumeId(resume.getId());
        archived.setVersionNo(2);
        archived.setSourceType(ResumeSourceType.AI_OPTIMIZED);
        archived.setResumeJson(Map.of("basics", Map.of("name", "导出简历 " + marker), "work", List.of()));
        archived.setCreatedBy(userId);
        archived.setDeletedAt(LocalDateTime.now());
        resumeVersionRepository.save(archived);

        CareerMaterial material = new CareerMaterial();
        material.setUserId(userId);
        material.setMaterialType(MaterialType.PROJECT_EXPERIENCE);
        material.setTitle("导出资料 " + marker);
        material.setContentJson(Map.of("role", "后端工程师"));
        material.setSourceText("原始文本 " + marker);
        careerMaterialRepository.save(material);

        JobDescription job = new JobDescription();
        job.setUserId(userId);
        job.setTitle("导出岗位 " + marker);
        job.setJdText("Java 后端岗位 " + marker);
        jobDescriptionRepository.save(job);

        ApplicationRecord application = new ApplicationRecord();
        application.setUserId(userId);
        application.setJobDescriptionId(job.getId());
        application.setResumeVersionId(active.getId());
        application.setStatus(ApplicationStatus.APPLIED);
        application.setCoverLetterText("导出求职信 " + marker);
        applicationRecordRepository.save(application);

        InterviewSession session = new InterviewSession();
        session.setUserId(userId);
        session.setSourceType(InterviewSourceType.EXTERNAL_RESUME);
        session.setInterviewMode(InterviewMode.TECHNICAL);
        session.setStatus(InterviewStatus.COMPLETED);
        session.setExternalResumeText("外部简历 " + marker);
        interviewSessionRepository.save(session);

        InterviewRecord record = new InterviewRecord();
        record.setSessionId(session.getId());
        record.setRoundNo(1);
        record.setQuestionText("导出问题 " + marker);
        record.setAnswerText("导出回答 " + marker);
        record.setRoundScore(80);
        record.setFeedbackJson(Map.of("summary", "结构清晰"));
        interviewRecordRepository.save(record);

        InterviewAnswerAsset asset = new InterviewAnswerAsset();
        asset.setUserId(userId);
        asset.setInterviewRecordId(record.getId());
        asset.setQuestionText("导出资产问题 " + marker);
        asset.setOriginalAnswerText("导出资产回答 " + marker);
        interviewAnswerAssetRepository.save(asset);

        InterviewAssetSection section = new InterviewAssetSection();
        section.setUserId(userId);
        section.setAssetId(asset.getId());
        section.setSectionKey("work");
        section.setMaterialId(material.getId());
        interviewAssetSectionRepository.save(section);

        CommunicationTemplate template = new CommunicationTemplate();
        template.setUserId(userId);
        template.setScene(TemplateScene.FOLLOW_UP);
        template.setTemplateType(CommunicationType.EMAIL);
        template.setName("导出自定义模板 " + marker);
        template.setBodyText("模板正文 " + marker);
        communicationTemplateRepository.save(template);

        CommunicationDraft draft = new CommunicationDraft();
        draft.setUserId(userId);
        draft.setResumeVersionId(active.getId());
        draft.setJobDescriptionId(job.getId());
        draft.setType(CommunicationType.COVER_LETTER);
        draft.setDraftText("草稿正文 " + marker);
        communicationDraftRepository.save(draft);

        PersonalProfile profile = new PersonalProfile();
        profile.setUserId(userId);
        profile.setFullName("导出用户 " + marker);
        personalProfileRepository.save(profile);

        AiConsent consent = new AiConsent();
        consent.setUserId(userId);
        consent.setEventType(ConsentStatus.GRANTED);
        consent.setPolicyVersion("test-policy-v1");
        consent.setProviderCode("test-provider");
        consent.setTaskScopesJson(List.of("RESUME_GENERATION"));
        consent.setDataCategoriesJson(List.of("CAREER_MATERIALS"));
        consent.setNoticeHash("test-notice-hash");
        aiConsentRepository.save(consent);
    }

    private void seedSystemTemplate() {
        CommunicationTemplate template = new CommunicationTemplate();
        template.setScene(TemplateScene.GENERAL);
        template.setTemplateType(CommunicationType.EMAIL);
        template.setName("系统模板不应导出");
        template.setBodyText("系统模板正文");
        template.setSystem(true);
        communicationTemplateRepository.save(template);
    }
}
