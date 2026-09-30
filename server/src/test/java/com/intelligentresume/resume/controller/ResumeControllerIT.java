package com.intelligentresume.resume.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ats.domain.AtsCheckResult;
import com.intelligentresume.ats.repository.AtsCheckResultRepository;
import com.intelligentresume.resume.domain.Resume;
import com.intelligentresume.resume.repository.ResumeRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 简历与版本控制器集成测试（MockMvc + H2 + Flyway）。
 *
 * <p>覆盖 T03 §9 中 8 个集成测试场景。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResumeControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AtsCheckResultRepository atsCheckResultRepository;
    @Autowired private ResumeRepository resumeRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 用户 A 的 access token（Order 1 注册后填充） */
    private static String tokenA;
    /** 用户 B 的 access token（Order 2 注册后填充） */
    private static String tokenB;
    /** 用户 A 创建的简历 ID */
    private static Long resumeIdA;
    private static Long versionIdA;

    // ---- 辅助方法 ----

    private String registerAndGetToken(String username, String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s","password":"%s"}
                                """.formatted(username, email, password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("data").get("accessToken").asText();
    }

    // ---- 注册用户 ----

    @Test
    @Order(1)
    @DisplayName("准备: 注册用户 A")
    void registerUserA() throws Exception {
        tokenA = registerAndGetToken("resume_user_a", "resume_a@example.com", "correcthorse");
        assertNotNull(tokenA);
    }

    @Test
    @Order(2)
    @DisplayName("准备: 注册用户 B")
    void registerUserB() throws Exception {
        tokenB = registerAndGetToken("resume_user_b", "resume_b@example.com", "correcthorse");
        assertNotNull(tokenB);
    }

    // ---- 1. 创建简历 ----

    @Test
    @Order(3)
    @DisplayName("POST /api/resumes 201 + Detail")
    void postCreate_201() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/resumes")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Java后端简历"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.title").value("Java后端简历"))
                .andReturn();

        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        resumeIdA = node.get("data").get("id").asLong();
    }

    // ---- 2. 列表 ----

    @Test
    @Order(4)
    @DisplayName("GET /api/resumes 返回当前用户列表")
    void getList_returnsOwn() throws Exception {
        mockMvc.perform(get("/api/resumes")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].title").value("Java后端简历"));
    }

    // ---- 3. 跨用户访问 ----

    @Test
    @Order(5)
    @DisplayName("GET /api/resumes/{id} 跨用户返回 40401")
    void getDetail_crossUser_returns40401() throws Exception {
        assertNotNull(resumeIdA, "resumeIdA 应已在 postCreate_201 中赋值");
        mockMvc.perform(get("/api/resumes/" + resumeIdA)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ---- 4. 更新 ----

    @Test
    @Order(6)
    @DisplayName("PUT /api/resumes/{id} 200")
    void putUpdate_200() throws Exception {
        mockMvc.perform(put("/api/resumes/" + resumeIdA)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"更新后的简历标题"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.title").value("更新后的简历标题"));
    }

    // ---- 5. 软删 ----

    @Test
    @Order(7)
    @DisplayName("DELETE /api/resumes/{id} 软删,后续 get 返回 40401")
    void delete_softDelete() throws Exception {
        // 先创建一份用于删除的简历
        MvcResult createResult = mockMvc.perform(post("/api/resumes")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"待删除简历"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long deleteId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        // 删除
        mockMvc.perform(delete("/api/resumes/" + deleteId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 删除后 get 应返回 40401
        mockMvc.perform(get("/api/resumes/" + deleteId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ---- 6. 保存版本 ----

    @Test
    @Order(8)
    @DisplayName("POST /api/resumes/{id}/versions 201 + versionNo=1")
    void postSaveVersion_firstVersion_201() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/resumes/" + resumeIdA + "/versions")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "resumeJson": {"basics": {"name": "Alice"}, "work": [{"company": "ACME"}]},
                                  "sourceType": "MANUAL"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.sourceType").value("MANUAL"))
                .andReturn();

        versionIdA = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 验证版本列表
        mockMvc.perform(get("/api/resumes/" + resumeIdA + "/versions")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].versionNo").value(1));
    }

    // ---- 7. 无效 JSON Resume ----

    @Test
    @Order(9)
    @DisplayName("无效 JSON Resume POST 返回 40001")
    void postInvalidJson_40001() throws Exception {
        // 缺少 basics 顶层字段
        mockMvc.perform(post("/api/resumes/" + resumeIdA + "/versions")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "resumeJson": {"work": [{"company": "ACME"}]},
                                  "sourceType": "MANUAL"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    // ---- 8. 未登录 ----

    @Test
    @Order(10)
    @DisplayName("未登录访问 POST 返回 40101(安全框架统一拦截)")
    void postWithoutAuth_40101() throws Exception {
        mockMvc.perform(post("/api/resumes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"未登录简历"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @Order(11)
    @DisplayName("ATS 溯源恢复只接收标识，并在详情和历史中返回服务端派生上下文")
    void restoreWithAtsProvenance_returnsValidatedContext() throws Exception {
        MvcResult job = mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Platform Engineer\",\"companyName\":\"Example\",\"jdText\":\"Java reliability\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        Long jobDescriptionId = objectMapper.readTree(job.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        AtsCheckResult result = new AtsCheckResult();
        result.setUserId(userId(tokenA));
        result.setResumeVersionId(versionIdA);
        result.setJobDescriptionId(jobDescriptionId);
        result.setIdempotencyKey("resume-ats-provenance");
        result.setRequestFingerprint("resume-ats-provenance-fingerprint");
        result.setTotalScore(BigDecimal.valueOf(72));
        result.setResultJson(Map.of(
                "analysisStatus", "COMPLETED",
                "aiInsights", Map.of(
                        "evidenceFindings", List.of(Map.of(
                                "section", "work",
                                "suggestion", "Quantify delivery impact")),
                        "prioritizedActions", List.of())));
        result = atsCheckResultRepository.save(result);

        mockMvc.perform(post("/api/resumes/" + resumeIdA + "/versions/" + versionIdA + "/restore")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"atsResultId\":%d,\"atsItem\":\"evidence:0\"}".formatted(result.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.generationContext.atsProvenance.resultId").value(result.getId()))
                .andExpect(jsonPath("$.data.generationContext.atsProvenance.sourceVersionId").value(versionIdA))
                .andExpect(jsonPath("$.data.generationContext.atsProvenance.mappedSection").value("work"))
                .andExpect(jsonPath("$.data.generationContext.atsProvenance.optimizationObjective")
                        .value("Quantify delivery impact"));

        // #50：版本列表为摘要投影——不返回 generationContext（大字段，编辑器仅从版本详情读取）
        mockMvc.perform(get("/api/resumes/" + resumeIdA + "/versions")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].generationContext").doesNotExist())
                .andExpect(jsonPath("$.data[0].templateCode").exists());
    }

    @Test
    @Order(12)
    @DisplayName("未提供 ATS 溯源时仍可按原有恢复契约创建版本")
    void restoreWithoutAtsProvenance_remainsCompatible() throws Exception {
        mockMvc.perform(post("/api/resumes/" + resumeIdA + "/versions/" + versionIdA + "/restore")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.restoredFromVersionId").value(versionIdA))
                .andExpect(jsonPath("$.data.generationContext").doesNotExist());
    }

    // ---- 乐观锁（#38） ----

    @Test
    @Order(13)
    @DisplayName("#38 乐观锁: 陈旧简历副本保存被拒绝，不再回写并发推进的 current_version_id")
    void staleResumeWrite_isRejectedByOptimisticLock() {
        Resume stale = resumeRepository.findById(resumeIdA).orElseThrow();

        // 模拟另一并发事务推进了版本指针（version 前进一格，指针指向 v1）
        jdbcTemplate.update("UPDATE resume SET version = version + 1, current_version_id = ? WHERE id = ?",
                versionIdA, resumeIdA);
        assertEquals(versionIdA, resumeRepository.findById(resumeIdA).orElseThrow().getCurrentVersionId());

        // 陈旧副本（旧 version、旧指针）保存 → 乐观锁冲突，由全局处理器映射 40901
        stale.setTitle("stale title must be rejected");
        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> resumeRepository.saveAndFlush(stale),
                "陈旧副本保存必须被乐观锁拒绝 → 全局处理器映射 40901");

        Resume fresh = resumeRepository.findById(resumeIdA).orElseThrow();
        assertNotEquals("stale title must be rejected", fresh.getTitle(), "陈旧写入不得落库");
        assertEquals(versionIdA, fresh.getCurrentVersionId(), "并发推进的版本指针不得被陈旧写回退");
    }

    private Long userId(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }
}
