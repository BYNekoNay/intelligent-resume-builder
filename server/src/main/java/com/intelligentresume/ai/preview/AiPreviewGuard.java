package com.intelligentresume.ai.preview;

import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 领域端点的预览令牌校验（R32 AC-R32-9 / AC-R32-10）。
 *
 * <p>为什么令牌走**请求头**而不是各领域 DTO：5 个 DTO 都改会连带改 20+ 处构造点，
 * 而它语义上本来就是"传输层协商"。服务端拿**自己组装出的 input** 重算令牌比对 ——
 * 客户端无法伪造成"预览过"，因为它拿不到服务端组装结果的哈希。
 *
 * <p>5 个领域共用一个实现，避免 5 份校验逻辑各自漂移。
 */
@Component
public class AiPreviewGuard {

    /** 与前端约定：所有走预览协议的领域端点都带这个头。 */
    public static final String HEADER = "X-AI-Preview-Token";

    private final AiPreviewService previewService;

    public AiPreviewGuard(AiPreviewService previewService) {
        this.previewService = previewService;
    }

    /**
     * @param type         任务类型
     * @param providerInput 服务端**自己组装**的 provider input（与 worker 同源）
     * @param headerToken   客户端回传的令牌，可为空
     */
    public void require(AiTaskType type, Map<String, Object> providerInput, String headerToken) {
        Map<String, Object> withToken = new LinkedHashMap<>(providerInput != null ? providerInput : Map.of());
        if (headerToken != null && !headerToken.isBlank()) {
            withToken.put(AiPreviewService.TOKEN_KEY, headerToken.trim());
        }
        AiPreviewService.TokenCheck check = previewService.verify(type, withToken);
        if (check == AiPreviewService.TokenCheck.MISSING) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "请先预览将要发送的内容并确认（缺少 " + HEADER + " 请求头）");
        }
        if (check == AiPreviewService.TokenCheck.STALE) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "预览内容与本次请求不一致（参数已改变）：请重新预览后再提交");
        }
    }
}
