package com.intelligentresume.ai.generation.service;

import com.intelligentresume.ai.generation.dto.JobGenerationRequest;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 资料选择器(确定性,不调 LLM)。
 *
 * <p>按用户指定的 INCLUDED/PREFERRED/EXCLUDED 以及资料自身的
 * usagePreference 进行分类。跨用户或不存在的 included/preferred ID
 * 抛 NOT_FOUND(不泄露存在性)。
 */
@Service
public class MaterialSelector {

    private final int maxSelected;

    public MaterialSelector(
            @Value("${app.ai.generation.max-selected-materials:30}") int maxSelected) {
        this.maxSelected = maxSelected;
    }

    public SelectionResult select(Long userId,
                                  List<CareerMaterial> allMaterials,
                                  JobGenerationRequest req) {
        Map<Long, CareerMaterial> materialMap = allMaterials.stream()
                .collect(Collectors.toMap(CareerMaterial::getId, Function.identity()));

        Set<Long> excludedIds = safeSet(req.excludedMaterialIds());
        Set<Long> includedIds = safeSet(req.includedMaterialIds());
        Set<Long> preferredIds = safeSet(req.preferredMaterialIds());
        // 排除优先于固定
        includedIds.removeAll(excludedIds);
        preferredIds.removeAll(excludedIds);
        preferredIds.removeAll(includedIds);

        // 校验 included: 必须存在且属于当前用户
        List<CareerMaterial> fixed = new ArrayList<>();
        for (Long id : includedIds) {
            CareerMaterial m = materialMap.get(id);
            if (m == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "资料不存在: " + id);
            }
            fixed.add(m);
        }

        // 校验 preferred: 必须存在且属于当前用户
        List<CareerMaterial> preferred = new ArrayList<>();
        for (Long id : preferredIds) {
            CareerMaterial m = materialMap.get(id);
            if (m == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "资料不存在: " + id);
            }
            preferred.add(m);
        }

        // excluded: 不存在的 ID 静默忽略
        List<CareerMaterial> excluded = new ArrayList<>();
        Map<Long, String> unselectedReasons = new LinkedHashMap<>();
        for (Long id : excludedIds) {
            CareerMaterial m = materialMap.get(id);
            if (m != null) {
                excluded.add(m);
                unselectedReasons.put(id, "USER_EXCLUDED");
            }
        }

        // normal: 不在任何用户指定列表中的资料
        Set<Long> specialIds = new HashSet<>();
        specialIds.addAll(includedIds);
        specialIds.addAll(preferredIds);
        specialIds.addAll(excludedIds);

        List<CareerMaterial> normal = allMaterials.stream()
                .filter(m -> !specialIds.contains(m.getId()))
                .collect(Collectors.toList());

        // 截断至 maxSelected
        int usedSlots = fixed.size() + preferred.size();
        if (normal.size() > maxSelected - usedSlots) {
            int limit = Math.max(0, maxSelected - usedSlots);
            List<CareerMaterial> truncated = normal.subList(limit, normal.size());
            for (CareerMaterial m : truncated) {
                unselectedReasons.put(m.getId(), "EXCEEDS_MAX_SELECTED");
            }
            normal = new ArrayList<>(normal.subList(0, limit));
        }

        return new SelectionResult(fixed, preferred, excluded, normal, unselectedReasons);
    }

    private Set<Long> safeSet(List<Long> ids) {
        return ids == null ? new HashSet<>() : new HashSet<>(ids);
    }

    public record SelectionResult(
            List<CareerMaterial> fixed,
            List<CareerMaterial> preferred,
            List<CareerMaterial> excluded,
            List<CareerMaterial> normal,
            Map<Long, String> unselectedReasons
    ) {
    }
}
