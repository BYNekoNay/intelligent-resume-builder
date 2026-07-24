package com.intelligentresume.scoring.repository;

import com.intelligentresume.scoring.domain.MatchResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 匹配评分结果仓储。跨用户校验通过 JOIN resume_version → resume 间接完成。
 */
public interface MatchResultRepository extends JpaRepository<MatchResult, Long> {

    /**
     * 跨用户安全查询：通过 resume_version → resume → user_id 间接校验归属。
     */
    @Query("SELECT mr FROM MatchResult mr, ResumeVersion rv, Resume r " +
            "WHERE mr.resumeVersionId = rv.id AND rv.resumeId = r.id " +
            "AND mr.id = :id AND r.userId = :userId")
    Optional<MatchResult> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    List<MatchResult> findByResumeVersionIdOrderByCreatedAtDesc(Long resumeVersionId);
}
