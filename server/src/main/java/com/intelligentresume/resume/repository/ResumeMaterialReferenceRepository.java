package com.intelligentresume.resume.repository;

import com.intelligentresume.resume.domain.ResumeMaterialReference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ResumeMaterialReferenceRepository extends JpaRepository<ResumeMaterialReference, Long> {
    List<ResumeMaterialReference> findByMaterialIdOrderByCreatedAtDesc(Long materialId);
}
