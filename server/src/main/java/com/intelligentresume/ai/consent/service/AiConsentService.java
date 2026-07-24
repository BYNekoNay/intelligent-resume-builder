package com.intelligentresume.ai.consent.service;

import com.intelligentresume.ai.consent.domain.AiConsent;
import com.intelligentresume.ai.consent.domain.ConsentStatus;
import com.intelligentresume.ai.consent.dto.ConsentResponse;
import com.intelligentresume.ai.consent.dto.GrantConsentRequest;
import com.intelligentresume.ai.consent.repository.AiConsentRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 同意服务。事件溯源模型:每次操作追加新事件,不修改历史。
 */
@Service
public class AiConsentService {

    private static final String PROVIDER_CODE = "bailian";

    private final AiConsentRepository repository;

    @Value("${app.ai.consent.policy-version:v1.0.0}")
    private String policyVersion;

    public AiConsentService(AiConsentRepository repository) {
        this.repository = repository;
    }

    /**
     * 授权 AI 数据处理。校验 policyVersion 后追加 GRANTED 事件。
     */
    @Transactional
    public ConsentResponse grant(GrantConsentRequest req, Long userId) {
        if (!policyVersion.equals(req.policyVersion())) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "隐私政策版本不匹配,当前版本: " + policyVersion);
        }
        if (!PROVIDER_CODE.equals(req.providerCode())) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "当前仅支持 AI 提供者: " + PROVIDER_CODE);
        }

        AiConsent consent = new AiConsent();
        consent.setUserId(userId);
        consent.setEventType(ConsentStatus.GRANTED);
        consent.setPolicyVersion(req.policyVersion());
        consent.setProviderCode(req.providerCode());
        consent.setTaskScopesJson(req.taskScopes());
        consent.setDataCategoriesJson(req.dataCategories());
        consent.setNoticeHash(req.noticeHash());

        consent = repository.save(consent);
        return toResponse(consent);
    }

    /**
     * 获取用户最新的同意事件。无事件时返回 null。
     */
    @Transactional(readOnly = true)
    public ConsentResponse current(Long userId) {
        return repository.findFirstByUserIdOrderByCreatedAtDesc(userId)
                .map(this::toResponse)
                .orElse(null);
    }

    /**
     * 撤回同意。追加 WITHDRAWN 事件,不修改历史。
     */
    @Transactional
    public ConsentResponse withdraw(Long userId) {
        // 获取最新事件以继承 policyVersion 和 providerCode
        AiConsent latest = repository.findFirstByUserIdOrderByCreatedAtDesc(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "尚无同意记录,无法撤回"));

        AiConsent consent = new AiConsent();
        consent.setUserId(userId);
        consent.setEventType(ConsentStatus.WITHDRAWN);
        consent.setPolicyVersion(latest.getPolicyVersion());
        consent.setProviderCode(latest.getProviderCode());
        consent.setTaskScopesJson(latest.getTaskScopesJson());
        consent.setDataCategoriesJson(latest.getDataCategoriesJson());
        consent.setNoticeHash(latest.getNoticeHash());

        consent = repository.save(consent);
        return toResponse(consent);
    }

    /**
     * 检查用户是否有有效的 AI 同意(最新事件为 GRANTED)。
     * 供 AiTaskService 在创建任务前调用。
     */
    @Transactional(readOnly = true)
    public boolean hasValidConsent(Long userId) {
        return repository.findFirstByUserIdOrderByCreatedAtDesc(userId)
                .map(c -> c.getEventType() == ConsentStatus.GRANTED)
                .orElse(false);
    }

    private ConsentResponse toResponse(AiConsent c) {
        return new ConsentResponse(
                c.getId(),
                c.getPolicyVersion(),
                c.getProviderCode(),
                c.getTaskScopesJson(),
                c.getDataCategoriesJson(),
                c.getEventType(),
                c.getCreatedAt()
        );
    }
}
