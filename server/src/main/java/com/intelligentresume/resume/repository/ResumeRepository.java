package com.intelligentresume.resume.repository;

import com.intelligentresume.resume.domain.Resume;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ResumeRepository extends JpaRepository<Resume, Long> {

    Optional<Resume> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Resume r WHERE r.id = :id AND r.userId = :userId")
    Optional<Resume> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    List<Resume> findByUserIdOrderByUpdatedAtDesc(Long userId);

    List<Resume> findByUserIdAndJobDescriptionIdAndDeletedAtIsNull(Long userId, Long jobDescriptionId);
}
