package com.intelligentresume.careermaterial.repository;

import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.careermaterial.dto.CareerMaterialListRow;
import com.intelligentresume.careermaterial.dto.CareerMaterialTypeCount;
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

    /**
     * 列表读模型（ideation #1）：只投影摘要列 + contentJson + 「原文非空」标志，
     * 不传输 MEDIUMTEXT sourceText；类型过滤下推到 SQL，不再内存过滤。
     */
    @Query("""
            select new com.intelligentresume.careermaterial.dto.CareerMaterialListRow(
                material.id, material.materialType, material.title, material.usagePreference,
                material.updatedAt, material.contentJson,
                case when trim(coalesce(material.sourceText, '')) <> '' then true else false end)
            from CareerMaterial material
            where material.userId = :userId
              and (:materialType is null or material.materialType = :materialType)
            order by material.updatedAt desc
            """)
    List<CareerMaterialListRow> findListRowsByUserId(
            @Param("userId") Long userId,
            @Param("materialType") MaterialType materialType);

    @Query("""
            select material from CareerMaterial material
            where material.userId = :userId
              and (:materialType is null or material.materialType = :materialType)
              and (:usagePreference is null or material.usagePreference = :usagePreference)
              and (:query is null
                   or lower(material.title) like concat('%', lower(:query), '%') escape '\\'
                   or lower(coalesce(material.sourceText, '')) like concat('%', lower(:query), '%') escape '\\'
                   or lower(material.contentJsonText) like concat('%', lower(:query), '%') escape '\\')
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
