package com.intelligentresume.ai.consent.service;

import com.intelligentresume.ai.consent.domain.AiConsent;
import com.intelligentresume.ai.consent.dto.ConsentRequest;
import com.intelligentresume.ai.consent.dto.ConsentResponse;
import com.intelligentresume.ai.consent.repository.AiConsentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 同意领域服务。
 *
 * <p>骨架:T06 落地同意事件写读,撤回后阻止新建任务。
 */
@Service
public class ConsentService {

    private static final java.util.List<String> ALL_AI_TASK_SCOPES = java.util.List.of(
            "JOB_GENERATION", "MATERIAL_RESUME_GENERATION", "INLINE_OPTIMIZE",
            "ACHIEVEMENT_GUIDANCE", "COMMUNICATION_GENERATE", "INTERVIEW");
    private static final java.util.List<String> ALL_AI_DATA_CATEGORIES = java.util.List.of(
            "CAREER_MATERIAL", "JOB_DESCRIPTION", "RESUME", "RAW_MATERIAL_TEXT",
            "INTERVIEW_ANSWER", "TEXT_SELECTION");

    private final AiConsentRepository repository;
    private final String providerCode;

    public ConsentService(AiConsentRepository repository, @Value("${app.ai.provider}") String providerCode) {
        this.repository = repository;
        this.providerCode = providerCode;
    }

    @Transactional
    public ConsentResponse grant(ConsentRequest request, Long userId) {
        AiConsent c = new AiConsent();
        c.setUserId(userId);
        c.setEventType(AiConsent.ConsentEventType.GRANTED);
        c.setPolicyVersion(request.policyVersion());
        c.setProviderCode(providerCode);
        // Consent currently gates all AI entry points through isConsented(). Persist the
        // effective global scope rather than accepting a narrower, misleading client claim.
        c.setTaskScopes(ALL_AI_TASK_SCOPES);
        c.setDataCategories(ALL_AI_DATA_CATEGORIES);
        c.setNoticeHash(request.noticeHash());
        AiConsent saved = repository.save(c);
        return toResponse(saved);
    }

    @Transactional
    public ConsentResponse withdraw(Long userId) {
        AiConsent c = new AiConsent();
        c.setUserId(userId);
        c.setEventType(AiConsent.ConsentEventType.WITHDRAWN);
        c.setPolicyVersion("n/a");
        c.setProviderCode("n/a");
        c.setTaskScopes(java.util.List.of());
        c.setDataCategories(java.util.List.of());
        c.setNoticeHash("n/a");
        AiConsent saved = repository.save(c);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public ConsentResponse current(Long userId) {
        return repository.findFirstByUserIdOrderByCreatedAtDesc(userId)
                .map(this::toResponse)
                .orElse(null);
    }

    public boolean isConsented(Long userId) {
        return repository.findFirstByUserIdOrderByCreatedAtDesc(userId)
                .map(c -> c.getEventType() == AiConsent.ConsentEventType.GRANTED)
                .orElse(false);
    }

    private ConsentResponse toResponse(AiConsent consent) {
        return new ConsentResponse(
                consent.getId(),
                consent.getEventType(),
                consent.getCreatedAt(),
                consent.getPolicyVersion(),
                consent.getProviderCode(),
                consent.getTaskScopes(),
                consent.getDataCategories());
    }
}
