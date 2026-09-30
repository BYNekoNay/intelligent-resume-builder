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

    /** 最新事件按 (created_at, id) 降序：同毫秒并发撤回/授权时以自增 id 保证稳定顺序。 */
    Optional<AiConsent> findFirstByUserIdOrderByCreatedAtDescIdDesc(Long userId);
}
