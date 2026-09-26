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
    @DisplayName("未登录访问 POST/GET 返回 40101(安全框架统一拦截)")
    void postWithoutAuth_40101() throws Exception {
        mockMvc.perform(post("/api/career-materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "materialType": "SKILL",
                                  "title": "未登录资料",
                                  "contentJson": {"name": "test"}
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));

        mockMvc.perform(get("/api/career-materials/search"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    @Order(10)
    @DisplayName("只有标题的资料被拒绝,避免无证据内容进入生成边界")
    void postTitleOnlyMaterial_40001() throws Exception {
        mockMvc.perform(post("/api/career-materials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "materialType": "WORK_EXPERIENCE",
                                  "title": "临时资料校验",
                                  "contentJson": {"title": "临时资料校验", "sourceText": ""}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("来源原文")));
    }

    @Test
    @Order(11)
    @DisplayName("跨用户 PATCH/DELETE 返回 404(反枚举),所有者不受影响")
    void crossUserPatchAndDelete_notFound() throws Exception {
        // 账号 A(token)创建一条资料
        MvcResult createResult = mockMvc.perform(post("/api/career-materials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "materialType": "EDUCATION",
                                  "title": "跨用户防护资料",
                                  "contentJson": {"school": "示例大学"}
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long materialId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        // 账号 B 尝试 PATCH / DELETE,均应 NOT_FOUND(不泄露资源存在性)
        String otherToken = registerAndGetToken("cm_attacker", "cm_attacker@example.com", "correcthorse");
        mockMvc.perform(patch("/api/career-materials/" + materialId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "被越权改写", "contentJson": {"school": "篡改"}}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));

        mockMvc.perform(delete("/api/career-materials/" + materialId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));

        // 账号 A 仍可正常读,且内容未被 B 篡改
        mockMvc.perform(get("/api/career-materials/" + materialId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(materialId))
                .andExpect(jsonPath("$.data.title").value("跨用户防护资料"))
                .andExpect(jsonPath("$.data.contentJson.school").value("示例大学"));

        // 清理:A 正常删除自己的资料,验证所有者操作不受 B 的越权尝试影响
        mockMvc.perform(delete("/api/career-materials/" + materialId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @Order(12)
    @DisplayName("契约锚定: 独立用户下 list?type= 只返回精确匹配类型")
    void getList_typeFilterExactMatch_freshUser() throws Exception {
        String listToken = registerAndGetToken("cm_listfilter", "cm_listfilter@example.com", "correcthorse");

        long[] skillIds = new long[2];
        for (int i = 0; i < 2; i++) {
            MvcResult result = mockMvc.perform(post("/api/career-materials")
                            .header("Authorization", "Bearer " + listToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "materialType": "SKILL",
                                      "title": "技能 %d",
                                      "contentJson": {"name": "Java %d"}
                                    }
                                    """.formatted(i, i)))
                    .andExpect(status().isCreated())
                    .andReturn();
            skillIds[i] = objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("data").get("id").asLong();
        }
        mockMvc.perform(post("/api/career-materials")
                        .header("Authorization", "Bearer " + listToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "materialType": "EDUCATION",
                                  "title": "教育经历",
                                  "contentJson": {"school": "示例大学"}
                                }
                                """))
                .andExpect(status().isCreated());

        // type=SKILL 只返回 2 条 SKILL,不混入其他类型
        mockMvc.perform(get("/api/career-materials")
                        .param("type", "SKILL")
                        .header("Authorization", "Bearer " + listToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].materialType").value("SKILL"))
                .andExpect(jsonPath("$.data[1].materialType").value("SKILL"));

        // 无 type 时返回全部 3 条
        mockMvc.perform(get("/api/career-materials")
                        .header("Authorization", "Bearer " + listToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    @Order(13)
    @DisplayName("契约锚定: contentJson 上限按配置 65536 字节生效")
    void postContentJsonSizeLimit_boundaryBehavior() throws Exception {
        // 超过 64KB(65536)→ 400 40001
        mockMvc.perform(post("/api/career-materials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "materialType", "COURSE",
                                "title", "超限课程",
                                "contentJson", java.util.Map.of("data", "x".repeat(70000))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("超过大小限制")));

        // 接近上限(60KB < 65536)→ 201,锚定配置值不低于 60KB
        mockMvc.perform(post("/api/career-materials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "materialType", "COURSE",
                                "title", "临界限额课程",
                                "contentJson", java.util.Map.of("data", "x".repeat(60000))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.materialType").value("COURSE"));
    }
}
