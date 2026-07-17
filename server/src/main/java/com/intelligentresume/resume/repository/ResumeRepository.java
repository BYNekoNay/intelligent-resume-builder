package com.intelligentresume.resume.repository;

import com.intelligentresume.resume.domain.Resume;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResumeRepository extends JpaRepository<Resume, Long> {

    Optional<Resume> findByIdAndUserId(Long id, Long userId);

    List<Resume> findByUserIdOrderByUpdatedAtDesc(Long userId);
}