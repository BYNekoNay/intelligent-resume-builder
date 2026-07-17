package com.intelligentresume.careermaterial.service;

import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.careermaterial.dto.CareerMaterialCreateRequest;
import com.intelligentresume.careermaterial.dto.CareerMaterialResponse;
import com.intelligentresume.resume.dto.ResumeMaterialReferenceResponse;
import com.intelligentresume.resume.repository.ResumeMaterialReferenceRepository;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 职业资料领域服务。
 *
 * <p>骨架:T04 落地类型过滤 / 引用偏好 / 软删 / 历史快照。
 */
@Service
public class CareerMaterialService {

    private final CareerMaterialRepository repository;
    private final ResumeMaterialReferenceRepository referenceRepository;

    public CareerMaterialService(CareerMaterialRepository repository,
                                 ResumeMaterialReferenceRepository referenceRepository) {
        this.repository = repository;
        this.referenceRepository = referenceRepository;
    }

    @Transactional
    public CareerMaterialResponse create(CareerMaterialCreateRequest request, Long userId) {
        CareerMaterial m = new CareerMaterial();
        m.setUserId(userId);
        m.setMaterialType(request.materialType());
        m.setTitle(request.title());
        m.setContentJson(request.contentJson());
        m.setSourceText(request.sourceText());
        m.setUsagePreference(request.usagePreference() == null ? UsagePreference.NORMAL : request.usagePreference());
        CareerMaterial saved = repository.save(m);
        return toResponse(saved);
    }

    public List<CareerMaterialResponse> list(Long userId, MaterialType type) {
        List<CareerMaterial> rows = (type == null)
                ? repository.findByUserIdOrderByUpdatedAtDesc(userId)
                : repository.findByUserIdAndMaterialTypeOrderByUpdatedAtDesc(userId, type);
        return rows.stream().map(this::toResponse).toList();
    }

    public CareerMaterialResponse get(Long id, Long userId) {
        CareerMaterial m = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return toResponse(m);
    }

    @Transactional
    public CareerMaterialResponse update(Long id, CareerMaterialCreateRequest request, Long userId) {
        CareerMaterial m = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (request.title() != null) m.setTitle(request.title());
        if (request.contentJson() != null) m.setContentJson(request.contentJson());
        if (request.sourceText() != null) m.setSourceText(request.sourceText());
        if (request.usagePreference() != null) m.setUsagePreference(request.usagePreference());
        if (request.materialType() != null) m.setMaterialType(request.materialType());
        return toResponse(repository.save(m));
    }

    @Transactional
    public void softDelete(Long id, Long userId) {
        CareerMaterial m = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        m.setDeletedAt(java.time.LocalDateTime.now());
        repository.save(m);
    }

    public List<ResumeMaterialReferenceResponse> references(Long id, Long userId) {
        repository.findByIdAndUserId(id, userId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return referenceRepository.findByMaterialIdOrderByCreatedAtDesc(id).stream()
                .map(reference -> new ResumeMaterialReferenceResponse(reference.getId(), reference.getResumeVersionId(),
                        reference.getMaterialId(), reference.getSelectionStatus(), reference.getOutputPath(),
                        reference.getSourceSnapshotJson(), reference.getSelectionReason(), reference.getCreatedAt()))
                .toList();
    }

    private CareerMaterialResponse toResponse(CareerMaterial m) {
        return new CareerMaterialResponse(m.getId(), m.getMaterialType(), m.getTitle(), m.getContentJson(),
                m.getSourceText(), m.getUsagePreference(), m.getCreatedAt(), m.getUpdatedAt());
    }
}
