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

    @Test
    void healthExposesDegradedDependenciesInsteadOfClaimingEverythingIsUp() {
        AiProvider provider = mock(AiProvider.class);
        when(provider.isAvailable()).thenReturn(false);
        PdfServiceClient pdfServiceClient = mock(PdfServiceClient.class);
        when(pdfServiceClient.checkHealth()).thenReturn(false);
        SystemController controller = new SystemController(
                new AiProviderRegistry(List.of(provider)), pdfServiceClient);

        ApiResponse<SystemHealthResponse> response = controller.health(mock(HttpServletRequest.class));

        assertEquals("DEGRADED", response.data().status());
        assertEquals("DOWN", response.data().checks().get(1).status());
        assertEquals("DOWN", response.data().checks().get(2).status());
        assertEquals("pdf-export", response.data().capabilities().get(7));
    }
}
