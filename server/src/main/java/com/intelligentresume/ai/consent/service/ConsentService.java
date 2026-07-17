package com.intelligentresume.ai.consent.service;

import com.intelligentresume.ai.consent.domain.AiConsent;
import com.intelligentresume.ai.consent.dto.ConsentRequest;
import com.intelligentresume.ai.consent.dto.ConsentResponse;
import com.intelligentresume.ai.consent.repository.AiConsentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 同意领域服务。
 *
 * <p>骨架:T06 落地同意事件写读,撤回后阻止新建任务。
 */
@Service
public class ConsentService {

    private final AiConsentRepository repository;

    public ConsentService(AiConsentRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ConsentResponse grant(ConsentRequest request, Long userId) {
        AiConsent c = new AiConsent();
        c.setUserId(userId);
        c.setEventType(AiConsent.ConsentEventType.GRANTED);
        c.setPolicyVersion(request.policyVersion());
        c.setProviderCode(request.providerCode());
        c.setTaskScopes(request.taskScopes());
        c.setDataCategories(request.dataCategories());
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
