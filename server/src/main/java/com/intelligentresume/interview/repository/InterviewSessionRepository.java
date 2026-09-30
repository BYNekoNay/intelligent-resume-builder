package com.intelligentresume.interview.repository;

import com.intelligentresume.interview.domain.CompletionReason;
import com.intelligentresume.interview.domain.ExecutionMode;
import com.intelligentresume.interview.domain.InterviewMode;
import com.intelligentresume.interview.domain.InterviewSession;
import com.intelligentresume.interview.domain.InterviewSourceType;
import com.intelligentresume.interview.domain.InterviewStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, Long> {
    Optional<InterviewSession> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM InterviewSession s WHERE s.id = :id AND s.userId = :userId")
    Optional<InterviewSession> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 历史列表读模型（ideation #50）：历史摘要只需要元数据与计数，
     * 不加载 {@code external_resume_text}（MEDIUMTEXT 简历原文）与
     * {@code current_question} 等大字段；仅 COMPLETED，支持按 JD 筛选，
     * 按 updatedAt 降序。
     */
    interface SessionSummaryProjection {
        Long getId();
        Long getJobDescriptionId();
        Long getResumeVersionId();
        InterviewSourceType getSourceType();
        InterviewMode getInterviewMode();
        ExecutionMode getExecutionMode();
        CompletionReason getCompletionReason();
        Integer getTargetQuestionCount();
        LocalDateTime getCreatedAt();
        LocalDateTime getUpdatedAt();
    }

    @Query("""
            SELECT s.id AS id, s.jobDescriptionId AS jobDescriptionId, s.resumeVersionId AS resumeVersionId,
                   s.sourceType AS sourceType, s.interviewMode AS interviewMode, s.executionMode AS executionMode,
                   s.completionReason AS completionReason, s.targetQuestionCount AS targetQuestionCount,
                   s.createdAt AS createdAt, s.updatedAt AS updatedAt
            FROM InterviewSession s
            WHERE s.userId = :userId
              AND s.status = :status
              AND (:jobDescriptionId IS NULL OR s.jobDescriptionId = :jobDescriptionId)
            ORDER BY s.updatedAt DESC, s.id DESC
            """)
    List<SessionSummaryProjection> findCompletedSummariesByUserId(@Param("userId") Long userId,
                                                                  @Param("status") InterviewStatus status,
                                                                  @Param("jobDescriptionId") Long jobDescriptionId);
}