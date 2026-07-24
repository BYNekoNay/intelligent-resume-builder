package com.intelligentresume.jobdescription.repository;

import com.intelligentresume.jobdescription.domain.JobDescription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JobDescriptionRepository extends JpaRepository<JobDescription, Long> {

    Optional<JobDescription> findByIdAndUserId(Long id, Long userId);

    List<JobDescription> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
