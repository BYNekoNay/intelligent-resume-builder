package com.intelligentresume.common.observability;

import com.intelligentresume.ai.task.domain.AiTaskType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("test")
class ObservabilityEndpointIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private AppObservability observability;

    @Test
    void prometheusEndpointExposesOperationalMetricsWithoutSensitiveLabels() throws Exception {
        observability.recordAiProviderCall(AiTaskType.JOB_GENERATION, "bailian", "deepseek-v3.2",
                false, AiFailureCategory.TIMEOUT, Duration.ofMillis(20));
        observability.recordAiTaskAttempt(AiTaskType.JOB_GENERATION, "failed",
                AiFailureCategory.SCHEMA_INVALID, 1, Duration.ofMillis(30));
        observability.recordPdfRender("classic", false, PdfFailureCategory.RENDER, Duration.ofMillis(10));

        String metrics = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(metrics.contains("resume_ai_provider_calls_total"));
        assertTrue(metrics.contains("failure_category=\"TIMEOUT\""));
        assertTrue(metrics.contains("resume_ai_schema_rejections_total"));
        assertTrue(metrics.contains("resume_pdf_render_calls_total"));
        assertTrue(metrics.contains("resume_ai_queue_depth"));
        assertTrue(metrics.contains("resume_pdf_queue_depth"));
        assertFalse(metrics.contains("user_id="));
        assertFalse(metrics.contains("task_id="));
        assertFalse(metrics.contains("trace_id="));
    }
}
