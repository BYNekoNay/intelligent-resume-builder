package com.intelligentresume.resume.repository;

import com.intelligentresume.resume.domain.ResumeVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ResumeVersionRepository extends JpaRepository<ResumeVersion, Long> {

    Optional<ResumeVersion> findByIdAndResumeId(Long id, Long resumeId);

    Optional<ResumeVersion> findByIdAndCreatedByAndDeletedAtIsNull(Long id, Long createdBy);

    List<ResumeVersion> findByResumeIdAndDeletedAtIsNullOrderByVersionNoDesc(Long resumeId);

    List<ResumeVersion> findByResumeIdAndDeletedAtIsNotNullOrderByVersionNoDesc(Long resumeId);

    Optional<ResumeVersion> findByResumeIdAndVersionNo(Long resumeId, Integer versionNo);

    @Query("SELECT MAX(v.versionNo) FROM ResumeVersion v WHERE v.resumeId = :resumeId")
    Integer findMaxVersionNoByResumeId(@Param("resumeId") Long resumeId);
}
