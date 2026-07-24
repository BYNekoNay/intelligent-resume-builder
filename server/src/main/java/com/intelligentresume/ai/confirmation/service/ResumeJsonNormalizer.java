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

        // 按决策类型分组处理
        for (ConfirmedDraftItem item : items) {
            if (item.decision() == Decision.REJECT) {
                removeAtPath(copy, item.outputPath());
            }
        }
        for (ConfirmedDraftItem item : items) {
            if (item.decision() == Decision.EDIT && item.editedValue() != null) {
                setAtPath(copy, item.outputPath(), item.editedValue());
            }
        }

        // 收集 ACCEPT 路径集合（用于 _pending 豁免）
        Set<String> acceptedPaths = new HashSet<>();
        for (ConfirmedDraftItem item : items) {
            if (item.decision() == Decision.ACCEPT) {
                acceptedPaths.add(item.outputPath());
            }
        }

        // 递归剥离 _source，检查残留 _pending
        stripMarkers(copy, "", acceptedPaths);

        return copy;
    }

    // ---- 递归剥离标记 ----

    @SuppressWarnings("unchecked")
    private void stripMarkers(Object node, String currentPath, Set<String> acceptedPaths) {
        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;

            // 剥离 _source
            map.remove("_source");

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

    // ---- 路径操作 ----

    @SuppressWarnings("unchecked")
    private void removeAtPath(Map<String, Object> root, String path) {
        String[] segments = path.split("\\.");
        Object current = root;

        // 导航到父节点
        for (int i = 0; i < segments.length - 1; i++) {
            current = navigateSegment(current, segments[i]);
            if (current == null) {
                return; // 路径不存在，静默忽略
            }
        }

        // 从父节点移除最后一个段
        String lastSegment = segments[segments.length - 1];
        Matcher m = INDEX_PATTERN.matcher(lastSegment);
        if (m.matches()) {
            String key = m.group(1);
            int index = Integer.parseInt(m.group(2));
            if (current instanceof Map) {
                Object listObj = ((Map<String, Object>) current).get(key);
                if (listObj instanceof List) {
                    List<Object> list = (List<Object>) listObj;
                    if (index < list.size()) {
                        list.remove(index);
                    }
                }
            }
        } else {
            if (current instanceof Map) {
                ((Map<String, Object>) current).remove(lastSegment);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void setAtPath(Map<String, Object> root, String path, Object value) {
        String[] segments = path.split("\\.");
        Object current = root;

        // 导航到父节点
        for (int i = 0; i < segments.length - 1; i++) {
            current = navigateSegment(current, segments[i]);
            if (current == null) {
                return;
            }
        }

        // 设置最后一个段
        String lastSegment = segments[segments.length - 1];
        Matcher m = INDEX_PATTERN.matcher(lastSegment);
        if (m.matches()) {
            String key = m.group(1);
            int index = Integer.parseInt(m.group(2));
            if (current instanceof Map) {
                Object listObj = ((Map<String, Object>) current).get(key);
                if (listObj instanceof List) {
                    List<Object> list = (List<Object>) listObj;
                    if (index < list.size()) {
                        list.set(index, value);
                    }
                }
            }
        } else {
            if (current instanceof Map) {
                ((Map<String, Object>) current).put(lastSegment, value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Object navigateSegment(Object current, String segment) {
        if (current == null) {
            return null;
        }
        Matcher m = INDEX_PATTERN.matcher(segment);
        if (m.matches()) {
            String key = m.group(1);
            int index = Integer.parseInt(m.group(2));
            if (!(current instanceof Map)) {
                return null;
            }
            Object listObj = ((Map<String, Object>) current).get(key);
            if (!(listObj instanceof List)) {
                return null;
            }
            List<Object> list = (List<Object>) listObj;
            return index < list.size() ? list.get(index) : null;
        } else {
            if (!(current instanceof Map)) {
                return null;
            }
            return ((Map<String, Object>) current).get(segment);
        }
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
