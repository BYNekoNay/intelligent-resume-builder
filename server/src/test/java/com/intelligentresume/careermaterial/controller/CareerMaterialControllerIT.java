package com.intelligentresume.careermaterial.controller;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 职业资料控制器集成测试（MockMvc + H2 + Flyway）。
 *
 * <p>覆盖 T04 §9 中 5 个集成测试场景。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CareerMaterialControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private static String token;

    // ---- 辅助方法 ----

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

    // ---- 注册用户 ----

    @Test
    @Order(1)
    @DisplayName("准备: 注册用户")
    void registerUser() throws Exception {
        token = registerAndGetToken("cm_user", "cm_user@example.com", "correcthorse");
        assertNotNull(token);
    }

    // ---- 1. 创建 ----

    @Test
    @Order(2)
    @DisplayName("POST /api/career-materials 201")
    void postCreate_201() throws Exception {
        mockMvc.perform(post("/api/career-materials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "materialType": "PROJECT_EXPERIENCE",
                                  "title": "订单系统重构",
                                  "contentJson": {"role": "后端开发", "tech": ["Java", "MySQL"]},
                                  "sourceText": "负责订单模块重构与性能优化",
                                  "usagePreference": "PREFERRED"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.materialType").value("PROJECT_EXPERIENCE"))
                .andExpect(jsonPath("$.data.title").value("订单系统重构"))
                .andExpect(jsonPath("$.data.usagePreference").value("PREFERRED"));
    }

    // ---- 2. 列表 ----

    @Test
    @Order(3)
    @DisplayName("GET /api/career-materials 返回本人列表")
    void getList_returnsOwn() throws Exception {
        // 再创建一条 SKILL 类型
        mockMvc.perform(post("/api/career-materials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "materialType": "SKILL",
                                  "title": "Java 技能",
                                  "contentJson": {"name": "Java", "level": "expert"}
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/career-materials")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    // ---- 3. 类型过滤 ----

    @Test
    @Order(4)
    @DisplayName("GET /api/career-materials?type=SKILL 仅返回技能")
    void getList_filterByType() throws Exception {
        mockMvc.perform(get("/api/career-materials")
                        .param("type", "SKILL")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].materialType").value("SKILL"));
    }

    @Test
    @Order(5)
    @DisplayName("workspace search treats wildcard characters literally and isolates users")
    void search_treatsWildcardsLiterallyAndIsolatesUsers() throws Exception {
        mockMvc.perform(post("/api/career-materials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "materialType": "HIGHLIGHT",
                                  "title": "Release 100% readiness",
                                  "contentJson": {"summary": "Release readiness"},
                                  "sourceText": "Literal percent evidence",
                                  "usagePreference": "PREFERRED"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/career-materials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "materialType": "HIGHLIGHT",
                                  "title": "Plain release readiness",
                                  "contentJson": {"summary": "No wildcard character"},
                                  "sourceText": "A separate release note",
                                  "usagePreference": "PREFERRED"
                                }
                                """))
                .andExpect(status().isCreated());

        String otherToken = registerAndGetToken("cm_other", "cm_other@example.com", "correcthorse");
        mockMvc.perform(post("/api/career-materials")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "materialType": "HIGHLIGHT",
                                  "title": "Other 100% readiness",
                                  "contentJson": {"summary": "Other user"},
                                  "sourceText": "Literal percent evidence",
                                  "usagePreference": "PREFERRED"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/career-materials/search")
                        .param("q", "%")
                        .param("type", "HIGHLIGHT")
                        .param("usagePreference", "PREFERRED")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("Release 100% readiness"))
                .andExpect(jsonPath("$.data.typeCounts.HIGHLIGHT").value(2));

        mockMvc.perform(get("/api/career-materials/search")
                        .param("q", "PERCENT EVIDENCE")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("Release 100% readiness"));
    }

    @Test
    @Order(6)
    @DisplayName("GET /api/career-materials/search returns paged workspace data")
    void search_returnsPagedWorkspaceData() throws Exception {
        mockMvc.perform(get("/api/career-materials/search")
                        .param("q", "Java")
                        .param("type", "SKILL")
                        .param("usagePreference", "NORMAL")
                        .param("page", "0")
                        .param("size", "25")
                        .param("sort", "title,asc")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].title", org.hamcrest.Matchers.startsWith("Java")))
                .andExpect(jsonPath("$.data.items[0].excerpt").isString())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(25))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.typeCounts.SKILL").value(1));

        mockMvc.perform(get("/api/career-materials/search")
                        .param("q", "Java")
                        .param("type", "SKILL")
                        .param("page", "99")
                        .param("size", "25")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page").value(99))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));
    }

    @Test
    @Order(7)
    @DisplayName("GET /api/career-materials/search rejects unsupported sorting")
    void search_rejectsInvalidSort() throws Exception {
        mockMvc.perform(get("/api/career-materials/search")
                        .param("sort", "title,desc")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    // ---- 4. 软删 ----

    @Test
    @Order(8)
    @DisplayName("DELETE 软删,资源不再出现在列表")
    void delete_softDeleted() throws Exception {
        // 创建一条用于删除的资料
        MvcResult createResult = mockMvc.perform(post("/api/career-materials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "materialType": "CERTIFICATE",
                                  "title": "待删除证书",
                                  "contentJson": {"name": "AWS SA"}
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long deleteId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        // 删除
        mockMvc.perform(delete("/api/career-materials/" + deleteId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 删除后不再出现在列表
        mockMvc.perform(get("/api/career-materials")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + deleteId + ")]").isEmpty());

        // 删除后 get 返回 404
        mockMvc.perform(get("/api/career-materials/" + deleteId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ---- 5. 未登录 ----

    @Test
    @Order(9)
    @DisplayName("未登录访问 POST 返回 403(Spring Security 拦截)")
    void postWithoutAuth_40101() throws Exception {
        // 偏差说明:同 T03,/api/career-materials 不在 permitAll 白名单,
        // Spring Security 返回 403 而非手册期望的 40101。
        mockMvc.perform(post("/api/career-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "materialType": "SKILL",
                                  "title": "未登录资料",
                                  "contentJson": {"name": "test"}
                                }
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/career-materials/search"))
                .andExpect(status().isForbidden());
    }
}
