package com.intelligentresume.export.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.export.domain.ExportStatus;
import com.intelligentresume.export.domain.ExportTask;
import com.intelligentresume.export.repository.ExportTaskRepository;
import com.intelligentresume.export.service.ExportStorageService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 导出控制器集成测试（MockMvc + H2 + Flyway）。
 * 覆盖：POST 202、GET 200、下载 PDF、跨用户 40401、过期 40401。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExportControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ExportTaskRepository exportTaskRepository;
    @Autowired private ExportStorageService storageService;

    private static String tokenA;
    private static String tokenB;
    private static Long versionId;
    private static Long exportTaskId;

    private String registerAndGetToken(String username, String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s","password":"%s"}
                                """.formatted(username, email, password)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("accessToken").asText();
    }

    @Test
    @Order(1)
    @DisplayName("准备: 注册用户 + 简历版本")
    void setup() throws Exception {
        exportTaskRepository.deleteAll();

        tokenA = registerAndGetToken("export_user_a", "export_user_a@example.com", "correcthorse");
        tokenB = registerAndGetToken("export_user_b", "export_user_b@example.com", "correcthorse");

        // 创建简历
        MvcResult resumeResult = mockMvc.perform(post("/api/resumes")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "导出测试简历"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        Long resumeId = objectMapper.readTree(resumeResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        // 保存简历版本
        MvcResult versionResult = mockMvc.perform(post("/api/resumes/" + resumeId + "/versions")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceType": "MANUAL",
                                  "resumeJson": {
                                    "basics": {"name": "测试用户", "label": "Java工程师", "summary": "5年经验"},
                                    "work": [{"company": "测试公司", "position": "高级工程师"}],
                                    "skills": [{"name": "Java"}, {"name": "Spring Boot"}]
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        versionId = objectMapper.readTree(versionResult.getResponse().getContentAsString())
                .get("data").get("id").asLong();
    }

    @Test
    @Order(2)
    @DisplayName("POST /api/exports/pdf 202")
    void postCreate_202() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/exports/pdf")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resumeVersionId": %d, "templateCode": "classic"}
                                """.formatted(versionId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.templateCode").value("classic"))
                .andReturn();

        exportTaskId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("taskId").asLong();
    }

    @Test
    @Order(3)
    @DisplayName("GET /api/exports/tasks/{id} 200")
    void getTask_200() throws Exception {
        mockMvc.perform(get("/api/exports/tasks/" + exportTaskId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value(exportTaskId))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @Order(4)
    @DisplayName("GET 任务跨用户返回 40401")
    void getTask_crossUser_40401() throws Exception {
        mockMvc.perform(get("/api/exports/tasks/" + exportTaskId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    @Order(5)
    @DisplayName("GET 文件跨用户返回 40401")
    void download_crossUser_40401() throws Exception {
        mockMvc.perform(get("/api/exports/files/" + exportTaskId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    @Order(6)
    @DisplayName("GET 文件 PENDING 状态返回 40401")
    void download_pending_40401() throws Exception {
        mockMvc.perform(get("/api/exports/files/" + exportTaskId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    @Order(7)
    @DisplayName("GET 文件成功状态返回 application/pdf")
    void download_returnsPdf() throws Exception {
        // 手动将任务设为 SUCCESS 并存储文件
        ExportTask task = exportTaskRepository.findById(exportTaskId).orElseThrow();
        ExportStorageService.StoredFile stored = storageService.store("fake-pdf-content".getBytes(), "pdf");
        task.setStatus(ExportStatus.SUCCESS);
        task.setStorageKey(stored.storageKey());
        task.setFileSizeBytes(stored.size());
        task.setSha256(stored.checksumSha256());
        task.setExpiresAt(LocalDateTime.now().plusHours(24));
        exportTaskRepository.save(task);

        mockMvc.perform(get("/api/exports/files/" + exportTaskId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("resume.pdf")));
    }

    @Test
    @Order(8)
    @DisplayName("GET 文件过期返回 40401")
    void download_expired_40401() throws Exception {
        // 将任务设为过期
        ExportTask task = exportTaskRepository.findById(exportTaskId).orElseThrow();
        String storageKey = task.getStorageKey();
        task.setExpiresAt(LocalDateTime.now().minusHours(1));
        exportTaskRepository.save(task);

        mockMvc.perform(get("/api/exports/files/" + exportTaskId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));

        ExportTask expired = exportTaskRepository.findById(exportTaskId).orElseThrow();
        Assertions.assertEquals(ExportStatus.EXPIRED, expired.getStatus());
        Assertions.assertNull(expired.getStorageKey());
        Assertions.assertNull(expired.getFileSizeBytes());
        Assertions.assertNull(expired.getSha256());
        Assertions.assertNull(storageService.read(storageKey));
    }

    @Test
    @Order(9)
    @DisplayName("POST 无效模板返回 40001")
    void create_invalidTemplate_40001() throws Exception {
        mockMvc.perform(post("/api/exports/pdf")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resumeVersionId": %d, "templateCode": "unknown"}
                                """.formatted(versionId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @Order(10)
    @DisplayName("未登录返回 403")
    void unauthenticated_403() throws Exception {
        mockMvc.perform(post("/api/exports/pdf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"resumeVersionId": 1, "templateCode": "classic"}
                                """))
                .andExpect(status().isForbidden());
    }
}
