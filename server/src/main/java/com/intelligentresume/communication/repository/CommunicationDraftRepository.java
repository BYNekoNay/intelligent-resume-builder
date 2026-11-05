package com.intelligentresume.communication.repository;
import com.intelligentresume.communication.domain.CommunicationDraft;
import com.intelligentresume.communication.domain.CommunicationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 沟通草稿仓储。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code findFirstBy...DraftText} 是 AI 落库前的幂等查重键：五列全等即视为同一条草稿，
 *       改查重口径（比如不再比正文）会让重试产生重复行。</li>
 *   <li>{@code findByUserIdOrderByCreatedAtDesc} 供账号导出使用，只按 userId 过滤 —— 依赖草稿行自身的 user_id，
 *       不要删掉该列的写入。</li>
 * </ul>
 */
public interface CommunicationDraftRepository extends JpaRepository<CommunicationDraft, Long> {
    Optional<CommunicationDraft> findFirstByUserIdAndResumeVersionIdAndJobDescriptionIdAndTypeAndDraftText(
            Long userId, Long resumeVersionId, Long jobDescriptionId, CommunicationType type, String draftText);

    /** 账号数据导出（#7）：当前用户全部沟通草稿。 */
    List<CommunicationDraft> findByUserIdOrderByCreatedAtDesc(Long userId);
}
