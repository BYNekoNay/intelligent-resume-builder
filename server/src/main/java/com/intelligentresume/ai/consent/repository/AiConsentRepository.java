package com.intelligentresume.ai.consent.repository;

import com.intelligentresume.ai.consent.domain.AiConsent;
import com.intelligentresume.ai.consent.domain.ConsentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * AI 同意事件仓储。事件溯源:仅追加,不修改。
 */
public interface AiConsentRepository extends JpaRepository<AiConsent, Long> {

    Optional<AiConsent> findFirstByUserIdAndEventTypeOrderByCreatedAtDesc(Long userId, ConsentStatus eventType);

    List<AiConsent> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<AiConsent> findFirstByUserIdOrderByCreatedAtDesc(Long userId);
}
