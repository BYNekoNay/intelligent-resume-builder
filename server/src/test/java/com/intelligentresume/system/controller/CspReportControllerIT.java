package com.intelligentresume.system.controller;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link CspReportController} HTTP 层集成测试。
 *
 * <p>核心断言是「**匿名可达**」：报告由浏览器自动发出、不带任何凭证，
 * SecurityConfig 若没放行，浏览器收到的 401 会被静默丢弃 —— 整个
 * Report-Only 观察期就变成「永远没有报告」的假绿（比没有观察更糟）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CspReportControllerIT {

    private static final String PATH = "/api/system/csp-reports";

    @Autowired private MockMvc mockMvc;
    @Autowired private MeterRegistry registry;

    @Test
    @DisplayName("匿名 POST（application/csp-report）→ 204，并按 directive 计数")
    void anonymousReport_isAcceptedAndCounted() throws Exception {
        double before = counter("script-src");

        mockMvc.perform(post(PATH)
                        .contentType("application/csp-report")
                        .content("{\"csp-report\": {\"effective-directive\": \"script-src\","
                                + " \"blocked-uri\": \"http://evil/x.js\"}}"))
                .andExpect(status().isNoContent());

        assertEquals(before + 1.0, counter("script-src"),
                "报告必须被计数 —— 只验 204 会漏掉「计数从未发生」的缺陷");
    }

    @Test
    @DisplayName("content-type 为 application/json 的实现也接受（204）")
    void jsonContentType_alsoAccepted() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"csp-report\": {\"violated-directive\": \"img-src\"}}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("垃圾 body 仍 204（消费方是浏览器，错误细节只帮攻击者探测）")
    void garbageBody_returns204NotError() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType("application/csp-report")
                        .content("garbage-not-json"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("不支持的 content-type（如 text/plain）→ 415（不冒充 CSP 上报端点）")
    void unsupportedContentType_rejected415() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType());
    }

    /**
     * 读计数；该 directive 序列尚不存在时返回 0.0。
     * 首个「before」读取必然发生在任何报告之前 —— 用 {@code get} 会抛 MeterNotFound，
     * 而不存在恰恰是合法的初始状态（等价于计数为 0）。
     */
    private double counter(String directive) {
        io.micrometer.core.instrument.Counter counter = registry.find("security_csp_violation_reports")
                .tag("directive", directive)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
