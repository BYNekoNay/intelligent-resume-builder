package com.intelligentresume.careermaterial.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.careermaterial.dto.*;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 职业资料 CRUD + 类型过滤 + 引用偏好。
 *
 * <p>关键约定:
 * <ul>
 *     <li>所有查询带 userId 条件,杜绝跨用户访问。</li>
 *     <li>软删设置 deleted_at;被历史快照引用的资料删除后快照仍可读。</li>
 *     <li>contentJson 字节数不超过 {@code app.career-material.content-json.max-bytes}。</li>
 * </ul>
 */
@Service
public class CareerMaterialService {

    private final CareerMaterialRepository repository;
    private final ObjectMapper objectMapper;
    private final int maxContentBytes;

    public CareerMaterialService(CareerMaterialRepository repository,
                                 ObjectMapper objectMapper,
                                 @Value("${app.career-material.content-json.max-bytes:65536}") int maxContentBytes) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.maxContentBytes = maxContentBytes;
    }

    @Transactional
    public CareerMaterialDetail create(CreateCareerMaterialRequest req, Long userId) {
        validateContentJsonSize(req.contentJson());

        CareerMaterial material = new CareerMaterial();
        material.setUserId(userId);
        material.setMaterialType(req.materialType());
        material.setTitle(req.title());
        material.setContentJson(req.contentJson());
        material.setSourceText(req.sourceText());
        material.setUsagePreference(req.usagePreference() != null ? req.usagePreference() : UsagePreference.NORMAL);
        repository.save(material);
        return toDetail(material);
    }

    @Transactional(readOnly = true)
    public List<CareerMaterialSummary> list(Long userId, MaterialType filter) {
        List<CareerMaterial> materials = (filter != null)
                ? repository.findByUserIdAndMaterialTypeOrderByUpdatedAtDesc(userId, filter)
                : repository.findByUserIdOrderByUpdatedAtDesc(userId);
        return materials.stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public CareerMaterialDetail get(Long id, Long userId) {
        return toDetail(findOwned(id, userId));
    }

    @Transactional
    public CareerMaterialDetail update(Long id, UpdateCareerMaterialRequest req, Long userId) {
        CareerMaterial material = findOwned(id, userId);
        if (req.title() != null && !req.title().isBlank()) {
            material.setTitle(req.title());
        }
        if (req.contentJson() != null) {
            validateContentJsonSize(req.contentJson());
            material.setContentJson(req.contentJson());
        }
        if (req.sourceText() != null) {
            material.setSourceText(req.sourceText());
        }
        if (req.usagePreference() != null) {
            material.setUsagePreference(req.usagePreference());
        }
        repository.save(material);
        return toDetail(material);
    }

    @Transactional
    public void softDelete(Long id, Long userId) {
        CareerMaterial material = findOwned(id, userId);
        material.setDeletedAt(LocalDateTime.now());
        repository.save(material);
    }

    // ---- helpers ----

    private CareerMaterial findOwned(Long id, Long userId) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "资料不存在"));
    }

    private void validateContentJsonSize(Object contentJson) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(contentJson);
            if (bytes.length > maxContentBytes) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "contentJson 超过大小限制 (" + maxContentBytes + " bytes)");
            }
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "contentJson 序列化失败");
        }
    }

    private CareerMaterialSummary toSummary(CareerMaterial m) {
        return new CareerMaterialSummary(m.getId(), m.getMaterialType(), m.getTitle(),
                m.getUsagePreference(), m.getUpdatedAt());
    }

    private CareerMaterialDetail toDetail(CareerMaterial m) {
        return new CareerMaterialDetail(m.getId(), m.getMaterialType(), m.getTitle(),
                m.getContentJson(), m.getSourceText(), m.getUsagePreference(),
                m.getCreatedAt(), m.getUpdatedAt());
    }
}
