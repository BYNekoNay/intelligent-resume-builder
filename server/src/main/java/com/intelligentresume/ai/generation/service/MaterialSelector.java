package com.intelligentresume.ai.generation.service;

import com.intelligentresume.ai.generation.dto.JobGenerationRequest;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.service.CareerMaterialEvidence;
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
 * <p>新人三问：①业务问题：用户发起岗位定制时可勾选"必须用/尽量用/排除"某些资料，
 * 剩下的资料也要按数量上限自动取舍——本类把这套规则算清楚，产出四段互不重叠的资料分组；
 * ②链路位置：只被生成任务的旧路径（{@code JobGenerationService#executeLegacyTask}）调用，
 * 新的快照路径不再二次筛选（确认时边界已定）；
 * ③改坏它用户会看到：勾选的资料没被用、被排除的资料又冒出来、或"必须用"的资料
 * 因无证据被悄悄丢弃（正确行为是报错）。
 *
 * <p>按用户指定的 INCLUDED/PREFERRED/EXCLUDED 以及资料自身的
 * usagePreference 进行分类。跨用户或不存在的 included/preferred ID
 * 抛 NOT_FOUND(不泄露存在性)。
 *
 * <p>确定性约束：{@code normal} 的截断（subList 取前 limit 条）按传入的
 * {@code allMaterials} 顺序进行，调用方必须按 (updated_at desc, id desc)
 * 传入资料；同毫秒并列时单键排序会让被截断丢弃的素材集合 run-to-run 变化。
 *
 * <p>要点：
 * <ul>
 *   <li><b>排除优先于固定/优选</b>：同一 ID 同时出现在 excluded 与 included 时会被从 included/preferred
 *       里剔除，且不报错 —— 排除语义优先，见 {@code select} 开头三行 removeAll。</li>
 *   <li>included 里出现"无有效证据"的资料直接 400（这是用户显式要求必须用的）；
 *       preferred 里的同类资料只静默剔除，不阻断。</li>
 *   <li>excluded 里不存在或不属于本人的 ID 静默忽略（用户可能在别处已经删掉了资料）。</li>
 *   <li>配额只约束 normal 段：fixed + preferred 先占名额，剩余名额给 normal，超出的记
 *       {@code EXCEEDS_MAX_SELECTED}。因此 fixed + preferred 超过上限时 normal 会被整体清空。</li>
 *   <li>返回的 {@code unselectedReasons} 目前只有单测在读（生产链路尚未消费），
 *       新增原因码前先确认到底有没有消费方。</li>
 * </ul>
 */
@Service
public class MaterialSelector {

    private final int maxSelected;

    public MaterialSelector(
            @Value("${app.ai.generation.max-selected-materials:30}") int maxSelected) {
        this.maxSelected = maxSelected;
    }

    /**
     * 四段分类：fixed（必须用）/ preferred（优先用）/ excluded（排除）/ normal（剩下能用的），
     * 四段互不重叠。顺序敏感，①→④ 每步业务含义：
     * ①先剔除排除项（排除语义永远赢，用户说不要的资料不许进任何段）；
     * ②判可用性（"必须用"的资料无有效证据直接 400，"优先用"的只静默剔除——显式要求值得一个明确报错，
     *   非硬性偏好不值得打断流程）；
     * ③校验归属（ID 必须真实存在且属于本人，不存在抛 NOT_FOUND，不泄露"资料存在但不属于你"）；
     * ④最后才截断 normal 到配额（fixed + preferred 先占名额）——先截断再校验会让被截掉的资料
     *   逃过可用性/归属检查。
     *
     * <p>{@code userId} 在方法体内**没有被使用**：越权安全完全依赖调用方传入的
     * {@code allMaterials} 已按用户过滤（{@code findByUserId...}）。改签名前先记住这一点。
     */
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

        Set<Long> unusableIds = allMaterials.stream()
                .filter(material -> !CareerMaterialEvidence.isReady(material))
                .map(CareerMaterial::getId)
                .collect(Collectors.toSet());
        if (!Collections.disjoint(includedIds, unusableIds)) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "必须使用的资料需要包含来源原文或有意义的结构化内容");
        }
        preferredIds.removeAll(unusableIds);

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
        for (CareerMaterial m : allMaterials) {
            if (unusableIds.contains(m.getId()) && excluded.stream().noneMatch(item -> item.getId().equals(m.getId()))) {
                excluded.add(m);
                unselectedReasons.put(m.getId(), "INVALID_EVIDENCE");
            }
        }

        // normal: 不在任何用户指定列表中的资料
        Set<Long> specialIds = new HashSet<>();
        specialIds.addAll(includedIds);
        specialIds.addAll(preferredIds);
        specialIds.addAll(excludedIds);

        List<CareerMaterial> normal = allMaterials.stream()
                .filter(m -> !specialIds.contains(m.getId()))
                .filter(m -> !unusableIds.contains(m.getId()))
                .collect(Collectors.toList());

        // 截断至 maxSelected：按传入顺序保留前 limit 条，所以 allMaterials 的排序就是"谁被保留"的规则
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
