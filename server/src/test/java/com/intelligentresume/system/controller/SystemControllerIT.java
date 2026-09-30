package com.intelligentresume.system.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SystemController HTTP 层集成测试：验证 health 信息收敛的安全语义。
 *
 * <p>匿名 /api/system/health 只暴露 service + status；完整明细收敛到认证端点 /api/system/health/detail。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SystemControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("GET /api/system/health 匿名 200，仅含 service/status，不含 checks/version/capabilities")
    void anonymousHealth_returnsSummaryOnly() throws Exception {
        mockMvc.perform(get("/api/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.service").value("intelligent-resume-server"))
                .andExpect(jsonPath("$.data.status").isNotEmpty())
                .andExpect(jsonPath("$.data.checks").doesNotExist())
                .andExpect(jsonPath("$.data.version").doesNotExist())
                .andExpect(jsonPath("$.data.capabilities").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/system/health/detail 未认证返回 401")
    void healthDetail_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/system/health/detail"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/system/health/detail 认证后返回完整 payload（version/capabilities/checks）")
    void healthDetail_authenticated_returnsFullPayload() throws Exception {
        String token = registerAndGetToken();

        mockMvc.perform(get("/api/system/health/detail")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.service").value("intelligent-resume-server"))
                .andExpect(jsonPath("$.data.version").isNotEmpty())
                .andExpect(jsonPath("$.data.capabilities").isArray())
                .andExpect(jsonPath("$.data.checks.length()").value(4))
                .andExpect(jsonPath("$.data.checks[0].capability").value("api"));
    }

    private String registerAndGetToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"system_probe_user\",\"email\":\"system-probe@example.com\",\"password\":\"correcthorse\"}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }
}
