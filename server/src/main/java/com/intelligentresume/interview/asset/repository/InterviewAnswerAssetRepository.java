package com.intelligentresume.interview.asset.repository;

import com.intelligentresume.interview.asset.domain.InterviewAnswerAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface InterviewAnswerAssetRepository extends JpaRepository<InterviewAnswerAsset, Long> {
    Optional<InterviewAnswerAsset> findByIdAndUserId(Long id, Long userId);

    @Query("""
            SELECT a FROM InterviewAnswerAsset a
            WHERE a.userId = :userId
              AND (:jobId IS NULL OR a.interviewRecordId IN (
                    SELECT r.id FROM InterviewRecord r, InterviewSession s
                    WHERE r.sessionId = s.id AND s.jobDescriptionId = :jobId AND s.userId = :userId))
              AND (:keyword IS NULL OR LOWER(a.questionText) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(a.originalAnswerText) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    OR LOWER(COALESCE(a.suggestedAnswerText, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
            ORDER BY a.updatedAt DESC
            """)
    List<InterviewAnswerAsset> search(@Param("userId") Long userId, @Param("jobId") Long jobId,
                                      @Param("keyword") String keyword);
}
