package com.intelligentresume.scoring.repository;

import com.intelligentresume.scoring.domain.MatchResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 匹配评分结果仓储。跨用户校验通过 JOIN resume_version → resume 间接完成。
 *
 * <p>要点：
 * <ul>
 *   <li>{@link #findByIdAndUserId} 是<b>唯一</b>安全的按 ID 读取方式；{@code findById} 不带归属校验，不要在控制器里直接用。</li>
 *   <li>{@link #findFirstBy...OrderByIdDesc} 取"最近一条"，用 ID 而非 created_at 排序 —— 同一秒内的多条也能稳定取到后写的。</li>
 *   <li>{@code findByResumeVersionIdOrderByCreatedAtDesc} 不做用户过滤，调用方必须已确认该版本归属。</li>
 * </ul>
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

    /**
     * 结果复用（#78）：同一（简历版本, JD, 规则版本）最近一次评分结果。
     * 用于重放/重复点击时复用既有行，避免无限追加等价记录。
     */
    Optional<MatchResult> findFirstByResumeVersionIdAndJobDescriptionIdAndRuleVersionOrderByIdDesc(
            Long resumeVersionId, Long jobDescriptionId, String ruleVersion);
}
