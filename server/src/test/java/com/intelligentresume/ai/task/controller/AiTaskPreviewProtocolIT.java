package com.intelligentresume.ai.task.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R32 预览协议（AC-R32-3 / AC-R32-4 / AC-R32-5）。
 *
 * <p>从 {@link AiTaskControllerIT} 拆出来是因为那个文件已经 400 行（行数棘轮要求拆文件而不是登记）。
 * 拆分的边界也很自然：那边测**任务生命周期**，这边测**预览协议**。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiTaskPreviewProtocolIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AiTaskRepository taskRepository;

    private static String token;

    @BeforeEach
    void setUp() throws Exception {
        if (token != null) {
            return;
        }
        String username = "preview-user";
        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","email":"%s","password":"%s"}
                                """.formatted(username, username + "@example.com", "Passw0rd!2026")))
                .andExpect(status().isCreated())
                .andReturn();
        token = objectMapper.readTree(registered.getResponse().getContentAsString())
                .get("data").get("accessToken").asText();
        mockMvc.perform(post("/api/ai/consent")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "policyVersion": "v1.2.0",
                                  "providerCode": "bailian",
                                  "taskScopes": ["RESUME_OPTIMIZE"],
                                  "dataCategories": ["RESUME"],
                                  "noticeHash": "preview-protocol-test"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("GRANTED"));
    }

    @Test
    @DisplayName("预览是只读动作：不落库，返回令牌与脱敏摘要")
    void previewIsReadOnly() throws Exception {
        long before = taskRepository.count();
        mockMvc.perform(post("/api/ai/tasks/preview")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskType": "RESUME_OPTIMIZE", "input": {"content": "联系 a@b.com 或 13800138000"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previewToken").isNotEmpty())
                .andExpect(jsonPath("$.data.includeContact").value(false))
                .andExpect(jsonPath("$.data.redactions").isArray());
        assertEquals(before, taskRepository.count(), "预览不能产生 ai_task 行");
    }

    @Test
    @DisplayName("不带 previewToken 创建 → 40001，且不落库")
    void createWithoutTokenRejected() throws Exception {
        long before = taskRepository.count();
        mockMvc.perform(post("/api/ai/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskType": "RESUME_OPTIMIZE", "input": {"prompt": "没预览就提交"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
        assertEquals(before, taskRepository.count(), "被拒绝的请求不得产生 ai_task 行");
    }

    @Test
    @DisplayName("预览后改动内容 → 令牌失效 40901，且不落库")
    void staleTokenRejected() throws Exception {
        String bodyWithOldToken = previewedBody("{\"prompt\": \"原始内容\"}");
        long before = taskRepository.count();
        mockMvc.perform(post("/api/ai/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithOldToken.replace("原始内容", "偷偷改过的内容")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
        assertEquals(before, taskRepository.count(), "令牌不匹配的请求不得产生 ai_task 行");
    }

    /** 走一遍真实预览拿令牌，再拼出创建请求体（客户端真实协议）。 */
    private String previewedBody(String inputJson) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode input =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(inputJson);
        com.fasterxml.jackson.databind.node.ObjectNode previewRequest = objectMapper.createObjectNode();
        previewRequest.put("taskType", "RESUME_OPTIMIZE");
        previewRequest.set("input", input.deepCopy());

        MvcResult preview = mockMvc.perform(post("/api/ai/tasks/preview")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(previewRequest)))
                .andExpect(status().isOk())
                .andReturn();
        String previewToken = objectMapper.readTree(preview.getResponse().getContentAsString())
                .get("data").get("previewToken").asText();

        input.put("_previewToken", previewToken);
        com.fasterxml.jackson.databind.node.ObjectNode body = objectMapper.createObjectNode();
        body.put("taskType", "RESUME_OPTIMIZE");
        body.set("input", input);
        return objectMapper.writeValueAsString(body);
    }
}
