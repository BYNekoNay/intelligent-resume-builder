package com.intelligentresume.communication.repository;
import com.intelligentresume.communication.domain.CommunicationDraft;
import com.intelligentresume.communication.domain.CommunicationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CommunicationDraftRepository extends JpaRepository<CommunicationDraft, Long> {
    Optional<CommunicationDraft> findFirstByUserIdAndResumeVersionIdAndJobDescriptionIdAndTypeAndDraftText(
            Long userId, Long resumeVersionId, Long jobDescriptionId, CommunicationType type, String draftText);
}
