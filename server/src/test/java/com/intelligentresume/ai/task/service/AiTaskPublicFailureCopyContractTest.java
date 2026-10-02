package com.intelligentresume.ai.task.service;

import com.intelligentresume.ai.consent.service.AiConsentService;
import com.intelligentresume.ai.ratelimit.AiQuotaService;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.dto.AiTaskStatusResponse;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.ai.worker.AiTaskWorkerProperties;
import com.intelligentresume.common.error.PublicFailureCopy;
import com.intelligentresume.common.observability.FailureCategoryClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * **行为级**门禁：AI 任务状态响应不得把 `ai_task.error_message` 里的 provider 原文透出去。
 *
 * <p>为什么不写成「源码里必须出现某个方法名」的静态断言：那只能证明**写法**像样，
 * 证明不了**行为**。这里直接构造一条带真实 provider 原文的任务、走一遍
 * {@link AiTaskService#toResponse}，断言最终出网关的字符串。
 */
class AiTaskPublicFailureCopyContractTest {

    private static final String RAW_PROVIDER_MESSAGE =
            "Resume generation failed: HTTP 429 quota exceeded for model qwen3.8-max";

    private final AiTaskService service = new AiTaskService(
            mock(AiTaskRepository.class),
            mock(AiConsentService.class),
            mock(AiQuotaService.class),
            mock(IdempotencyService.class),
            mock(AiTaskWorkerProperties.class),
            new PublicFailureCopy(new FailureCategoryClassifier()));

    @Test
    @DisplayName("状态响应里的 errorMessage 是稳定公开文案，不含 provider 原文片段")
    void statusResponseCarriesStablePublicCopy() {
        AiTask task = failedTask(RAW_PROVIDER_MESSAGE);

        AiTaskStatusResponse response = service.toResponse(task);

        assertEquals(PublicFailureCopy.AI_SERVICE_UNAVAILABLE, response.errorMessage());
        for (String fragment : List.of("qwen", "429", "quota", "Resume generation failed")) {
            assertFalse(response.errorMessage().contains(fragment),
                    "provider 原文片段「" + fragment + "」透出了网关：" + response.errorMessage());
        }
    }

    @Test
    @DisplayName("无错误（error_message 为空）时文案保持 null —— 不得凭空造出「失败」")
    void noErrorStaysNull() {
        AiTask task = failedTask(null);
        task.setStatus(AiTaskStatus.SUCCESS);

        AiTaskStatusResponse response = service.toResponse(task);

        assertEquals(AiTaskStatus.SUCCESS, response.status());
        assertNull(response.errorMessage(),
                "重试会清空 error_message：映射层若把 null 变成文案，UI 会把成功任务显示成失败");
    }

    private AiTask failedTask(String errorMessage) {
        AiTask task = new AiTask();
        task.setId(1L);
        task.setTaskType(AiTaskType.JOB_GENERATION);
        task.setStatus(AiTaskStatus.FAILED);
        task.setInputSnapshotJson(new HashMap<>());
        task.setRetryCount(0);
        task.setErrorMessage(errorMessage);
        return task;
    }
}
