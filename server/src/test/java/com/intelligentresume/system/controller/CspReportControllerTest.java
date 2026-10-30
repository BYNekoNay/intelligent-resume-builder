package com.intelligentresume.system.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.common.observability.AppObservability;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CspReportController} 单元测试：解析、标签封闭集、限长、恒 204。
 *
 * <p>指标断言直接读 {@link SimpleMeterRegistry}，不只断言「返回 204」——
 * 该端点的价值在「报告被计数」，只验状态码会把「计数从未发生」漏成绿。
 */
class CspReportControllerTest {

    private MeterRegistry registry;
    private CspReportController controller;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        AppObservability observability = new AppObservability(
                registry, null, null);
        controller = new CspReportController(new ObjectMapper(), observability);
    }

    @Test
    @DisplayName("合法报告：204，effective-directive 进指标")
    void validReport_countsByEffectiveDirective() {
        MockHttpServletRequest request = post(cspBody("script-src", "http://evil.example/x.js"));

        assertEquals(204, controller.report(request).getStatusCode().value());
        assertEquals(1.0, counter("script-src"));
    }

    @Test
    @DisplayName("缺 effective-directive 时回退 violated-directive（Firefox 老实现只发后者）")
    void fallsBackToViolatedDirective() {
        String body = """
                {"csp-report": {"violated-directive": "style-src", "blocked-uri": "inline"}}
                """;
        assertEquals(204, controller.report(post(body)).getStatusCode().value());
        assertEquals(1.0, counter("style-src"));
    }

    @Test
    @DisplayName("未知 directive 归入 other（防止伪造值撑爆标签基数）")
    void unknownDirective_mapsToOther() {
        String body = cspBody("ghost-directive-injected-by-attacker", "inline");

        assertEquals(204, controller.report(post(body)).getStatusCode().value());
        assertEquals(1.0, counter(CspReportController.TAG_OTHER));
        assertEquals(0.0, counter("ghost-directive-injected-by-attacker"));
    }

    @Test
    @DisplayName("垃圾 body：恒 204 且计 unparseable，绝不给浏览器报错细节")
    void garbageBody_still204AndCountedUnparseable() {
        assertEquals(204, controller.report(post("not-json-at-all")).getStatusCode().value());
        assertEquals(1.0, counter(CspReportController.TAG_UNPARSEABLE));
    }

    @Test
    @DisplayName("空 body：204 + unparseable")
    void emptyBody_still204() {
        assertEquals(204, controller.report(post("")).getStatusCode().value());
        assertEquals(1.0, counter(CspReportController.TAG_UNPARSEABLE));
    }

    @Test
    @DisplayName("超大 body 在反序列化之前被截断：截断后的残缺 JSON 计 unparseable")
    void oversizedBody_truncatedBeforeParsing() {
        StringBuilder huge = new StringBuilder("{\"csp-report\": {\"effective-directive\": \"");
        huge.append("a".repeat(CspReportController.MAX_BODY_BYTES * 2));
        huge.append("\"}}");

        // 截断点落在字符串中间 → JSON 已残缺 → unparseable；关键是不抛异常、不吃满内存
        assertEquals(204, controller.report(post(huge.toString())).getStatusCode().value());
        assertEquals(1.0, counter(CspReportController.TAG_UNPARSEABLE));
    }

    @Test
    @DisplayName("directive 大小写归一：SCRIPT-SRC 与 script-src 同一标签")
    void directiveCaseNormalized() {
        String body = cspBody("SCRIPT-SRC", "inline");

        assertEquals(204, controller.report(post(body)).getStatusCode().value());
        assertEquals(1.0, counter("script-src"));
    }

    @Test
    @DisplayName("多份报告累计（counter 语义）而非覆盖")
    void multipleReports_accumulate() {
        controller.report(post(cspBody("img-src", "http://e/x.png")));
        controller.report(post(cspBody("img-src", "http://e/y.png")));

        assertEquals(2.0, counter("img-src"));
    }

    // ---------- helpers ----------

    private String cspBody(String directive, String blocked) {
        return "{\"csp-report\": {\"effective-directive\": \"" + directive
                + "\", \"blocked-uri\": \"" + blocked + "\", \"document-uri\": \"http://site/page\"}}";
    }

    private MockHttpServletRequest post(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setContentType("application/csp-report");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    /**
     * 读计数；该 directive 序列尚不存在时返回 0.0（负向断言依赖这一语义：
     * 「伪造值没有自己的序列」= counter 缺席 = 0）。用 {@code get} 会在缺席时抛异常。
     */
    private double counter(String directive) {
        io.micrometer.core.instrument.Counter counter = registry.find("security_csp_violation_reports")
                .tag("directive", directive)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
