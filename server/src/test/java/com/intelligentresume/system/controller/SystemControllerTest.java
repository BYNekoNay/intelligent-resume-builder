package com.intelligentresume.system.controller;

import com.intelligentresume.ai.provider.AiProvider;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.export.service.PdfServiceClient;
import com.intelligentresume.system.dto.SystemHealthResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemControllerTest {

    private static final List<String> EXPECTED_CHECKS =
            List.of("api", "ai-provider", "ai-model-chain", "pdf-renderer");

    private SystemController controllerFor(AiProvider provider, boolean pdfHealthy) {
        PdfServiceClient pdfServiceClient = mock(PdfServiceClient.class);
        when(pdfServiceClient.checkHealth()).thenReturn(pdfHealthy);
        return new SystemController(new AiProviderRegistry(List.of(provider)), pdfServiceClient);
    }

    private void assertCheckOrderAndCapabilities(SystemHealthResponse data) {
        assertEquals(EXPECTED_CHECKS.size(), data.checks().size());
        for (int index = 0; index < EXPECTED_CHECKS.size(); index++) {
            assertEquals(EXPECTED_CHECKS.get(index), data.checks().get(index).capability(),
                    "checks 顺序与能力名是前端与告警依赖的契约");
        }
    }

    @Test
    void healthExposesDegradedDependenciesInsteadOfClaimingEverythingIsUp() {
        AiProvider provider = mock(AiProvider.class);
        when(provider.isAvailable()).thenReturn(false);
        SystemController controller = controllerFor(provider, false);

        ApiResponse<SystemHealthResponse> response = controller.health(mock(HttpServletRequest.class));

        assertCheckOrderAndCapabilities(response.data());
        assertEquals("DEGRADED", response.data().status());
        assertEquals("DOWN", response.data().checks().get(1).status());
        assertEquals("DOWN", response.data().checks().get(2).status());
        assertEquals("DOWN", response.data().checks().get(3).status());
        assertEquals("pdf-export", response.data().capabilities().get(7));
    }

    @Test
    void healthReportsChainDownWhenApiKeyIsConfiguredButNoModelIsSchedulable() {
        // 回归测试：这正是历史上把「AI 全线故障」掩盖成 UP 的组合 ——
        // 密钥有效（ai-provider = UP），但所有模型额度耗尽 / 冷却中（ai-model-chain = DOWN）。
        AiProvider provider = mock(AiProvider.class);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.availableModelCount()).thenReturn(0);
        SystemController controller = controllerFor(provider, true);

        ApiResponse<SystemHealthResponse> response = controller.health(mock(HttpServletRequest.class));

        assertCheckOrderAndCapabilities(response.data());
        assertEquals("UP", response.data().checks().get(1).status(), "密钥已配置");
        assertEquals("DOWN", response.data().checks().get(2).status(), "但没有可调度的模型");
        assertEquals("DEGRADED", response.data().status(), "整体必须降级，不能报 UP");
    }

    @Test
    void healthIsUpWhenAtLeastOneModelIsSchedulable() {
        AiProvider provider = mock(AiProvider.class);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.availableModelCount()).thenReturn(3);
        SystemController controller = controllerFor(provider, true);

        ApiResponse<SystemHealthResponse> response = controller.health(mock(HttpServletRequest.class));

        assertCheckOrderAndCapabilities(response.data());
        assertEquals("UP", response.data().status());
        assertEquals("UP", response.data().checks().get(2).status());
    }
}
