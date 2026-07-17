package com.intelligentresume.scoring.repository;

import com.intelligentresume.scoring.domain.MatchResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MatchResultRepository extends JpaRepository<MatchResult, Long> {

    Optional<MatchResult> findById(Long id);

    List<MatchResult> findByResumeVersionIdOrderByCreatedAtDesc(Long resumeVersionId);

    Optional<MatchResult> findFirstByResumeVersionIdAndJobDescriptionIdOrderByCreatedAtDesc(
            Long resumeVersionId, Long jobDescriptionId);
}