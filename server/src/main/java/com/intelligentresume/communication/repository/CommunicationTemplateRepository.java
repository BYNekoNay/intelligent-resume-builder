package com.intelligentresume.communication.repository;

import com.intelligentresume.communication.domain.CommunicationOutputLanguage;
import com.intelligentresume.communication.domain.CommunicationTemplate;
import com.intelligentresume.communication.domain.CommunicationType;
import com.intelligentresume.communication.domain.TemplateScene;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CommunicationTemplateRepository extends JpaRepository<CommunicationTemplate, Long> {

    @Query("""
            SELECT t FROM CommunicationTemplate t
            WHERE (t.userId = :userId OR t.userId IS NULL)
              AND (:scene IS NULL OR t.scene = :scene)
              AND (:type IS NULL OR t.templateType = :type)
              AND (:language IS NULL OR t.outputLanguage = :language)
            ORDER BY (CASE WHEN t.userId IS NULL THEN 0 ELSE 1 END), t.usageCount DESC, t.id ASC
            """)
    List<CommunicationTemplate> search(@Param("userId") Long userId,
                                       @Param("scene") TemplateScene scene,
                                       @Param("type") CommunicationType type,
                                       @Param("language") CommunicationOutputLanguage language);

    Optional<CommunicationTemplate> findByIdAndUserId(Long id, Long userId);
}
