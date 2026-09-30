package com.intelligentresume.resume.repository;

import com.intelligentresume.resume.domain.ResumeSourceType;
import com.intelligentresume.resume.domain.ResumeVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ResumeVersionRepository extends JpaRepository<ResumeVersion, Long> {

    Optional<ResumeVersion> findByIdAndResumeId(Long id, Long resumeId);

    Optional<ResumeVersion> findByIdAndCreatedByAndDeletedAtIsNull(Long id, Long createdBy);

    /**
     * 版本历史摘要投影（ideation #50）：只取元数据列，不加载 {@code resume_json}
     * （单版本上限 256KB）与 {@code generation_context}。
     */
    interface VersionSummaryProjection {
        Long getId();
        Integer getVersionNo();
        ResumeSourceType getSourceType();
        String getTemplateCode();
        String getOptimizationSummary();
        LocalDateTime getCreatedAt();
        LocalDateTime getDeletedAt();
        Long getRestoredFromVersionId();
    }

    @Query("""
            SELECT v.id AS id, v.versionNo AS versionNo, v.sourceType AS sourceType,
                   v.templateCode AS templateCode, v.optimizationSummary AS optimizationSummary,
                   v.createdAt AS createdAt, v.deletedAt AS deletedAt,
                   v.restoredFromVersionId AS restoredFromVersionId
            FROM ResumeVersion v
            WHERE v.resumeId = :resumeId AND v.deletedAt IS NULL
            ORDER BY v.versionNo DESC
            """)
    List<VersionSummaryProjection> findActiveSummariesByResumeId(@Param("resumeId") Long resumeId);

    @Query("""
            SELECT v.id AS id, v.versionNo AS versionNo, v.sourceType AS sourceType,
                   v.templateCode AS templateCode, v.optimizationSummary AS optimizationSummary,
                   v.createdAt AS createdAt, v.deletedAt AS deletedAt,
                   v.restoredFromVersionId AS restoredFromVersionId
            FROM ResumeVersion v
            WHERE v.resumeId = :resumeId AND v.deletedAt IS NOT NULL
            ORDER BY v.versionNo DESC
            """)
    List<VersionSummaryProjection> findArchivedSummariesByResumeId(@Param("resumeId") Long resumeId);

    Optional<ResumeVersion> findByResumeIdAndVersionNo(Long resumeId, Integer versionNo);

    @Query("SELECT MAX(v.versionNo) FROM ResumeVersion v WHERE v.resumeId = :resumeId")
    Integer findMaxVersionNoByResumeId(@Param("resumeId") Long resumeId);
}
