package com.intelligentresume.communication.repository;
import com.intelligentresume.communication.domain.CommunicationDraft;
import com.intelligentresume.communication.domain.CommunicationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CommunicationDraftRepository extends JpaRepository<CommunicationDraft, Long> {
    Optional<CommunicationDraft> findFirstByUserIdAndResumeVersionIdAndJobDescriptionIdAndTypeAndDraftText(
            Long userId, Long resumeVersionId, Long jobDescriptionId, CommunicationType type, String draftText);

    /** 账号数据导出（#7）：当前用户全部沟通草稿。 */
    List<CommunicationDraft> findByUserIdOrderByCreatedAtDesc(Long userId);
}
