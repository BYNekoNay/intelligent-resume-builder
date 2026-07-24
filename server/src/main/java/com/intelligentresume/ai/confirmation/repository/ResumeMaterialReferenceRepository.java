package com.intelligentresume.ai.confirmation.repository;

import com.intelligentresume.ai.confirmation.domain.ResumeMaterialReference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 简历版本资料引用仓储。
 */
public interface ResumeMaterialReferenceRepository extends JpaRepository<ResumeMaterialReference, Long> {

    List<ResumeMaterialReference> findByResumeVersionId(Long resumeVersionId);
}
