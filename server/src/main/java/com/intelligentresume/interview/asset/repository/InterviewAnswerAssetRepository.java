package com.intelligentresume.interview.asset.repository;

import com.intelligentresume.interview.asset.domain.InterviewAnswerAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 答案资产查询。
 *
 * <p>【小白导读】一句话：素材收藏夹（interview_answer_asset 表）的读入口；
 * search 一个方法吃下四个可空筛选条件，避免按条件拼四种查询。
 *
 * <p>要点：
 * <ul>
 *   <li>实体带 {@code @SQLRestriction("deleted_at IS NULL")}，因此这里的查询天然不含已删行。</li>
 *   <li>{@code search} 的每个条件都用 {@code :param IS NULL OR ...} 写法实现「可空筛选」，
 *       调用方需要先判空再传，不要传空串（空串不等于 null，会真的去匹配）。</li>
 *   <li>按 JD 筛选是经 {@code interview_record → interview_session} 子查询完成，且子查询内再带
 *       userId，双重限定避免越权。</li>
 * </ul>
 */
public interface InterviewAnswerAssetRepository extends JpaRepository<InterviewAnswerAsset, Long> {
    Optional<InterviewAnswerAsset> findByIdAndUserId(Long id, Long userId);

    /** 幂等查询：(userId, interviewRecordId) 已存在资产时返回已有资产。 */
    Optional<InterviewAnswerAsset> findByUserIdAndInterviewRecordId(Long userId, Long interviewRecordId);

    @Query("""
            SELECT a FROM InterviewAnswerAsset a
            WHERE a.userId = :userId
              AND (:jobId IS NULL OR a.interviewRecordId IN (
                    SELECT r.id FROM InterviewRecord r, InterviewSession s
                    WHERE r.sessionId = s.id AND s.jobDescriptionId = :jobId AND s.userId = :userId))
              AND (:interviewRecordId IS NULL OR a.interviewRecordId = :interviewRecordId)
              AND (:sectionKey IS NULL OR a.id IN (
                    SELECT s.assetId FROM InterviewAssetSection s
                    WHERE s.userId = :userId AND s.sectionKey = :sectionKey))
              AND (:keyword IS NULL OR LOWER(a.questionText) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(a.originalAnswerText) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(COALESCE(a.suggestedAnswerText, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
            ORDER BY a.updatedAt DESC
            """)
    List<InterviewAnswerAsset> search(@Param("userId") Long userId, @Param("jobId") Long jobId,
                                      @Param("interviewRecordId") Long interviewRecordId,
                                      @Param("sectionKey") String sectionKey,
                                      @Param("keyword") String keyword);
}
