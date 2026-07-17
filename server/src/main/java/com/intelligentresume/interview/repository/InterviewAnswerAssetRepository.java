package com.intelligentresume.interview.repository;

import com.intelligentresume.interview.domain.InterviewAnswerAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface InterviewAnswerAssetRepository extends JpaRepository<InterviewAnswerAsset, Long> {
    List<InterviewAnswerAsset> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query(value = "SELECT a.* FROM interview_answer_asset a "
            + "JOIN interview_record r ON r.id = a.interview_record_id "
            + "JOIN interview_session s ON s.id = r.session_id "
            + "WHERE a.user_id = :userId AND s.job_description_id = :jobDescriptionId "
            + "ORDER BY a.created_at DESC", nativeQuery = true)
    List<InterviewAnswerAsset> findByUserIdAndJobDescriptionIdOrderByCreatedAtDesc(
            @Param("userId") Long userId, @Param("jobDescriptionId") Long jobDescriptionId);
}
