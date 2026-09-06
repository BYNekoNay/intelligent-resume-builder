package com.intelligentresume.ai.confirmation.service;

import com.intelligentresume.ai.confirmation.dto.ConfirmedDraftItem;
import com.intelligentresume.ai.confirmation.dto.ConfirmedDraftItem.Decision;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 草稿 JSON 标准化器。
 *
 * <p>输入：含 {@code _source} / {@code _pending} 标记的草稿 JSON + 用户逐条决策。
 * <br>输出：剥离所有标记后的标准化 JSON。
 *
 * <p>规则：
 * <ul>
 *   <li>{@code _source}：自动剥离，保留其下所有业务字段。</li>
 *   <li>{@code _pending}：必须有对应的 ACCEPT / EDIT / REJECT 决策，
 *       否则抛 {@link ErrorCode#CONFLICT}（REQUIRE_USER_INPUT）。</li>
 *   <li>REJECT：从 JSON 中移除该路径。</li>
 *   <li>EDIT：用 editedValue 覆盖该路径。</li>
 * </ul>
 */
@Component
public class ResumeJsonNormalizer {

    private static final Pattern INDEX_PATTERN = Pattern.compile("(.+?)\\[(\\d+)]");
    private static final Object REMOVED = new Object();

    /**
     * 标准化草稿 JSON。
     *
     * @param draft 原始草稿（含 _source / _pending）
     * @param items 用户逐条决策
     * @return 标准化后的 JSON（深拷贝，不修改原 draft）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> normalize(Map<String, Object> draft, List<ConfirmedDraftItem> items) {
        Map<String, Object> copy = (Map<String, Object>) deepCopy(draft);
        Map<String, ConfirmedDraftItem> decisions = new HashMap<>();
        Set<String> acceptedPaths = new HashSet<>();
        for (ConfirmedDraftItem item : items) {
            decisions.put(item.outputPath(), item);
            if (item.decision() == Decision.ACCEPT) {
                acceptedPaths.add(item.outputPath());
            }
        }

        // 按原始树递归应用决策。不能先删除数组项再按原路径编辑，
        // 否则 work[0] 被拒绝后 work[1] 会漂移为 work[0]。
        Object normalized = normalizeNode(copy, "", decisions, acceptedPaths);
        if (normalized == REMOVED || !(normalized instanceof Map)) {
            throw new BusinessException(ErrorCode.VALIDATION, "草稿根节点不能被移除");
        }

        return (Map<String, Object>) normalized;
    }

    @SuppressWarnings("unchecked")
    private Object normalizeNode(Object node, String currentPath,
                                 Map<String, ConfirmedDraftItem> decisions,
                                 Set<String> acceptedPaths) {
        ConfirmedDraftItem decision = decisions.get(currentPath);
        if (decision != null) {
            if (decision.decision() == Decision.REJECT) {
                return REMOVED;
            }
            if (decision.decision() == Decision.EDIT && decision.editedValue() != null) {
                Object edited = deepCopy(decision.editedValue());
                stripMarkers(edited, currentPath, Set.of());
                return edited;
            }
        }

        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;
            map.remove("_source");
            map.remove("_sources");
            if (map.containsKey("_pending")) {
                if (!isPathAccepted(currentPath, acceptedPaths)) {
                    throw new BusinessException(ErrorCode.CONFLICT,
                            "残留 _pending 未决策: " + currentPath);
                }
                map.remove("_pending");
            }
            Iterator<Map.Entry<String, Object>> iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Object> entry = iterator.next();
                String childPath = currentPath.isEmpty()
                        ? entry.getKey() : currentPath + "." + entry.getKey();
                Object child = normalizeNode(entry.getValue(), childPath, decisions, acceptedPaths);
                if (child == REMOVED) {
                    iterator.remove();
                } else {
                    entry.setValue(child);
                }
            }
            return map;
        }

        if (node instanceof List) {
            List<Object> source = (List<Object>) node;
            List<Object> normalized = new ArrayList<>(source.size());
            for (int i = 0; i < source.size(); i++) {
                String childPath = currentPath + "[" + i + "]";
                Object child = normalizeNode(source.get(i), childPath, decisions, acceptedPaths);
                if (child != REMOVED) {
                    normalized.add(child);
                }
            }
            return normalized;
        }
        return node;
    }

    // ---- 递归剥离标记 ----

    @SuppressWarnings("unchecked")
    private void stripMarkers(Object node, String currentPath, Set<String> acceptedPaths) {
        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;

            // 剥离 _source
            map.remove("_source");
            map.remove("_sources");

            // 检查 _pending
            if (map.containsKey("_pending")) {
                // 当前路径或父路径被 ACCEPT 则豁免
                if (!isPathAccepted(currentPath, acceptedPaths)) {
                    throw new BusinessException(ErrorCode.CONFLICT,
                            "残留 _pending 未决策: " + currentPath);
                }
                map.remove("_pending");
            }

            // 递归处理子节点
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String childPath = currentPath.isEmpty() ? entry.getKey() : currentPath + "." + entry.getKey();
                stripMarkers(entry.getValue(), childPath, acceptedPaths);
            }
        } else if (node instanceof List) {
            List<Object> list = (List<Object>) node;
            for (int i = 0; i < list.size(); i++) {
                String childPath = currentPath + "[" + i + "]";
                stripMarkers(list.get(i), childPath, acceptedPaths);
            }
        }
    }

    /**
     * 检查路径是否被 ACCEPT 覆盖。支持父路径 ACCEPT 覆盖子路径。
     */
    private boolean isPathAccepted(String path, Set<String> acceptedPaths) {
        if (acceptedPaths.contains(path)) {
            return true;
        }
        // 检查父路径是否被 ACCEPT（如 "work[0]" ACCEPT 覆盖 "work[0].highlights[1]"）
        for (String accepted : acceptedPaths) {
            if (path.startsWith(accepted + ".") || path.startsWith(accepted + "[")) {
                return true;
            }
        }
        return false;
    }

    // ---- 深拷贝 ----

    @SuppressWarnings("unchecked")
    private Object deepCopy(Object obj) {
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(k, deepCopy(v)));
            return copy;
        }
        if (obj instanceof List) {
            List<Object> list = (List<Object>) obj;
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(deepCopy(item)));
            return copy;
        }
        return obj; // String, Number, Boolean, null 不可变
    }
}
