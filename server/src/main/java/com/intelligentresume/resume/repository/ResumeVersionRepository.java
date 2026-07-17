package com.intelligentresume.resume.repository;

import com.intelligentresume.resume.domain.ResumeVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResumeVersionRepository extends JpaRepository<ResumeVersion, Long> {

    Optional<ResumeVersion> findByIdAndResumeId(Long id, Long resumeId);

    List<ResumeVersion> findByResumeIdOrderByVersionNoDesc(Long resumeId);

    Optional<ResumeVersion> findFirstByResumeIdOrderByVersionNoDesc(Long resumeId);
}