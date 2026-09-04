package com.intelligentresume.jobdescription.repository;

import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.dto.JobDescriptionReference;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JobDescriptionRepository extends JpaRepository<JobDescription, Long> {

    interface ReferenceProjection {
        Long getId();
        String getTitle();
        String getCompanyName();
    }

    Optional<JobDescription> findByIdAndUserId(Long id, Long userId);

    @Query("""
            SELECT jd.id AS id, jd.title AS title, jd.companyName AS companyName
            FROM JobDescription jd
            WHERE jd.id = :id AND jd.userId = :userId
            """)
    Optional<ReferenceProjection> findReferenceByIdAndUserId(@Param("id") Long id,
                                                             @Param("userId") Long userId);

    List<JobDescription> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
