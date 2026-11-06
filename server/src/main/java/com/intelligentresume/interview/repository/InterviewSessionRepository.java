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

/**
 * 面试会话的查询入口。
 *
 * <p>【小白导读】一句话：会话表 interview_session 的读入口。普通读用 {@code findByIdAndUserId}，
 * 「读了还要写」的流程一律走 {@code findByIdAndUserIdForUpdate}（悲观行锁，数据库层面排队），
 * 历史列表走投影只取元数据、不搬大字段。
 *
 * <p>要点：
 * <ul>
 *   <li>所有按 id 的查询都带 {@code userId}：越权与不存在统一返回空，由调用方转 404。</li>
 *   <li>{@code findByIdAndUserIdForUpdate} 是写路径的强制入口，用于把「读状态 → 判断 → 写」串行化。</li>
 *   <li>历史列表走 {@link SessionSummaryProjection}，避免加载简历原文等大字段。</li>
 * </ul>
 */
public interface InterviewSessionRepository extends JpaRepository<InterviewSession, Long> {
    /** 只读查询，不加锁；需要写或需要状态稳定时请改用带 {@code ForUpdate} 的版本。 */
    Optional<InterviewSession> findByIdAndUserId(Long id, Long userId);

    /** 账号数据导出（#7）：当前用户全部面试会话（含外部简历原文等大字段），创建时间升序。 */
    List<InterviewSession> findByUserIdOrderByCreatedAtAscIdAsc(Long userId);

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