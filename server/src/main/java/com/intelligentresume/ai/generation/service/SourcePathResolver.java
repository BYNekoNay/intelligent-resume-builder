package com.intelligentresume.ai.generation.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 来源路径解析器。解析 "work[0].highlights[2]" 形式的路径,
 * 在 Map/List 嵌套结构中导航。
 */
@Component
public class SourcePathResolver {

    private static final Pattern INDEX_PATTERN = Pattern.compile("(.+?)\\[(\\d+)]");

    /**
     * 按路径导航到目标节点。路径无效时返回 null。
     */
    @SuppressWarnings("unchecked")
    public Object resolve(Map<String, Object> root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        Object current = root;
        String[] segments = path.split("\\.");
        for (String segment : segments) {
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
                current = ((Map<String, Object>) current).get(key);
                if (!(current instanceof List)) {
                    return null;
                }
                List<Object> list = (List<Object>) current;
                if (index >= list.size()) {
                    return null;
                }
                current = list.get(index);
            } else {
                if (!(current instanceof Map)) {
                    return null;
                }
                current = ((Map<String, Object>) current).get(segment);
            }
        }
        return current;
    }
}
