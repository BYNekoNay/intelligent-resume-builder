package com.intelligentresume.careermaterial.repository;

import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CareerMaterialRepository extends JpaRepository<CareerMaterial, Long> {

    Optional<CareerMaterial> findByIdAndUserId(Long id, Long userId);

    List<CareerMaterial> findByUserIdOrderByUpdatedAtDesc(Long userId);

    List<CareerMaterial> findByUserIdAndMaterialTypeOrderByUpdatedAtDesc(Long userId, MaterialType materialType);

    long countByUserId(Long userId);
}
