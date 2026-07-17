package com.intelligentresume.ai.generation.service;

import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 资料筛选:EXCLUDED 必排除;PREFERRED / INCLUDED 推入;其余按 NORMAL 加入。
 *
 * <p>返回 {@link SelectedMaterials} 含 selected 列表、unselected 列表与原因字符串,
 * 供 AI 草稿与 UI 回显。
 */
@Component
public class MaterialSelector {

    public SelectedMaterials select(List<CareerMaterial> all,
                                    Set<Long> preferred,
                                    Set<Long> included,
                                    Set<Long> excluded) {
        if (preferred == null) preferred = Set.of();
        if (included == null) included = Set.of();
        if (excluded == null) excluded = Set.of();

        List<CareerMaterial> selected = new ArrayList<>();
        List<CareerMaterial> unselected = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        for (CareerMaterial m : all) {
            if (excluded.contains(m.getId())
                    || m.getUsagePreference() == UsagePreference.EXCLUDED) {
                unselected.add(m);
                reasons.add("EXCLUDED: " + m.getId() + " (" + m.getTitle() + ")");
                continue;
            }
            // PREFERRED 永远先收;INCLUDED 后续;NORMAL 其余
            if (preferred.contains(m.getId())
                    || m.getUsagePreference() == UsagePreference.PREFERRED) {
                selected.add(m);
                reasons.add("PREFERRED: " + m.getId() + " (" + m.getTitle() + ")");
                continue;
            }
            if (included.contains(m.getId()) || !included.isEmpty()) {
                // 当用户显式 include 列表时,不在 include 中也不算普通可选
                if (included.contains(m.getId())) {
                    selected.add(m);
                    reasons.add("INCLUDED: " + m.getId() + " (" + m.getTitle() + ")");
                    continue;
                }
                unselected.add(m);
                reasons.add("NOT_IN_INCLUDED: " + m.getId() + " (" + m.getTitle() + ")");
                continue;
            }
            // NORMAL
            selected.add(m);
            reasons.add("NORMAL: " + m.getId() + " (" + m.getTitle() + ")");
        }

        return new SelectedMaterials(selected, unselected, reasons);
    }

    public record SelectedMaterials(
            List<CareerMaterial> selected,
            List<CareerMaterial> unselected,
            List<String> reasons
    ) {}
}
