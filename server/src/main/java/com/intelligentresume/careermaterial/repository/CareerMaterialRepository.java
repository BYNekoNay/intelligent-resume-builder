package com.intelligentresume.careermaterial.repository;

import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.careermaterial.dto.CareerMaterialTypeCount;
import com.intelligentresume.careermaterial.dto.CareerMaterialSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CareerMaterialRepository extends JpaRepository<CareerMaterial, Long> {

    Optional<CareerMaterial> findByIdAndUserId(Long id, Long userId);

    List<CareerMaterial> findByUserIdOrderByUpdatedAtDesc(Long userId);

    @Query("""
            select new com.intelligentresume.careermaterial.dto.CareerMaterialSummary(
                material.id, material.materialType, material.title,
                material.usagePreference, material.updatedAt)
            from CareerMaterial material
            where material.userId = :userId
              and (:materialType is null or material.materialType = :materialType)
            order by material.updatedAt desc
            """)
    List<CareerMaterialSummary> findSummaries(
            @Param("userId") Long userId,
            @Param("materialType") MaterialType materialType);

    @Query("""
            select material from CareerMaterial material
            where material.userId = :userId
              and (:materialType is null or material.materialType = :materialType)
              and (:usagePreference is null or material.usagePreference = :usagePreference)
              and (:query is null
                   or lower(material.title) like concat('%', lower(:query), '%') escape '\\'
                   or lower(coalesce(material.sourceText, '')) like concat('%', lower(:query), '%') escape '\\')
            """)
    Page<CareerMaterial> search(
            @Param("userId") Long userId,
            @Param("materialType") MaterialType materialType,
            @Param("usagePreference") UsagePreference usagePreference,
            @Param("query") String query,
            Pageable pageable);

    @Query("""
            select new com.intelligentresume.careermaterial.dto.CareerMaterialTypeCount(
                material.materialType, count(material))
            from CareerMaterial material
            where material.userId = :userId
            group by material.materialType
            """)
    List<CareerMaterialTypeCount> countByType(@Param("userId") Long userId);

    long countByUserId(Long userId);
}
