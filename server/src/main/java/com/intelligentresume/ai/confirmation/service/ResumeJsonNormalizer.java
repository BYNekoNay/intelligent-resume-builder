package com.intelligentresume.ai.confirmation.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 AI 草稿(包括 {@code _source} / {@code _pending})深度剥离,生成可直接入库的 JSON Resume 树。
 */
@Component
public class ResumeJsonNormalizer {

    private static final String SOURCE_KEY = "_source";
    private static final String PENDING_KEY = "_pending";

    public Map<String, Object> normalize(Map<String, Object> draftJson) {
        if (draftJson == null) return Map.of();
        return (Map<String, Object>) scrub(draftJson);
    }

    private Object scrub(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (SOURCE_KEY.equals(key) || PENDING_KEY.equals(key)) continue;
                result.put(key, scrub(e.getValue()));
            }
            return result;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(scrub(item));
            return out;
        }
        return node;
    }
}
