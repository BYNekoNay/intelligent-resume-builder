package com.intelligentresume.ai.generation.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 来源路径解析器。解析 "work[0].highlights[2]" 形式的路径,
 * 在 Map/List 嵌套结构中导航。
 *
 * <p>要点：
 * <ul>
 *   <li>只支持 {@code a.b[0].c} 这种"点分层级 + 方括号下标"的形式；连续下标
 *       （{@code work[0][1]}）不支持：正则会把 key 取成 {@code work[0]}，查不到就返回 null。</li>
 *   <li>下标只认非负十进制数（正则 {@code \d+}），负索引/变量表达式一律不匹配。</li>
 *   <li>任何一步走不通（父节点不是 Map/List、下标越界、键不存在）都返回 null，
 *       <b>不抛异常</b> —— 调用方必须自己判断 null，不要假设一定有值。</li>
 *   <li>解析对象是 JSON 反序列化出来的 Map/List；不要传实体对象，本类不做类型转换。</li>
 * </ul>
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
