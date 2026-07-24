package com.intelligentresume.ai.generation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 草稿 Schema 校验器。校验 AI 输出的结构化草稿是否符合约定格式。
 *
 * <p>规则:
 * <ol>
 *   <li>顶层键只能是 basics / work / education / skills / projects / certificates</li>
 *   <li>数组元素必须包含 _source 或 _pending 之一(互斥)</li>
 *   <li>Map 类型值若含 _source 和 _pending 则互斥校验</li>
 *   <li>序列化字节数 ≤ max-output-bytes</li>
 *   <li>schemaVersion 不匹配抛 VALIDATION</li>
 * </ol>
 */
@Component
public class JobGenerationSchemaValidator {

    static final String CURRENT_VERSION = "v1.0.0";
    private static final Set<String> ALLOWED_TOP_KEYS =
            Set.of("basics", "work", "education", "skills", "projects", "certificates");

    private final int maxOutputBytes;
    private final ObjectMapper objectMapper;

    public JobGenerationSchemaValidator(
            @Value("${app.ai.generation.max-output-bytes:65536}") int maxOutputBytes,
            ObjectMapper objectMapper) {
        this.maxOutputBytes = maxOutputBytes;
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("unchecked")
    public void validate(Map<String, Object> draft, String schemaVersion) {
        if (!CURRENT_VERSION.equals(schemaVersion)) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "Schema 版本不匹配, 当前: " + CURRENT_VERSION + ", 传入: " + schemaVersion);
        }
        if (draft == null || draft.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "草稿为空");
        }

        // 1. 顶层键校验
        for (String key : draft.keySet()) {
            if (!ALLOWED_TOP_KEYS.contains(key)) {
                throw new BusinessException(ErrorCode.VALIDATION, "不允许的顶层字段: " + key);
            }
        }

        // 2. 递归校验 _source/_pending
        for (Map.Entry<String, Object> entry : draft.entrySet()) {
            validateValue(entry.getValue(), entry.getKey());
        }

        // 3. 字节数校验
        checkByteSize(draft);
    }

    @SuppressWarnings("unchecked")
    private void validateValue(Object value, String path) {
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            boolean hasSource = map.containsKey("_source");
            boolean hasPending = map.containsKey("_pending");
            if (hasSource && hasPending) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        path + ": _source 与 _pending 不能同时存在");
            }
            // 递归校验子节点
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!entry.getKey().startsWith("_")) {
                    validateValue(entry.getValue(), path + "." + entry.getKey());
                }
            }
        } else if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item instanceof Map) {
                    Map<String, Object> mapItem = (Map<String, Object>) item;
                    boolean hasSource = mapItem.containsKey("_source");
                    boolean hasPending = mapItem.containsKey("_pending");
                    if (hasSource == hasPending) {
                        throw new BusinessException(ErrorCode.VALIDATION,
                                path + "[" + i + "]: 数组元素必须包含 _source 或 _pending 之一");
                    }
                }
                validateValue(item, path + "[" + i + "]");
            }
        }
    }

    private void checkByteSize(Map<String, Object> draft) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(draft);
            if (bytes.length > maxOutputBytes) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "草稿大小 " + bytes.length + " 字节超过上限 " + maxOutputBytes);
            }
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "草稿序列化失败");
        }
    }
}
