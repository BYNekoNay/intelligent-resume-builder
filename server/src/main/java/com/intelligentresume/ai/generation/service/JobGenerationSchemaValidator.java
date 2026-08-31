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
import java.util.HashSet;

/**
 * 草稿 Schema 校验器。校验 AI 输出的结构化草稿是否符合约定格式。
 *
 * <p>规则:
 * <ol>
 *   <li>顶层键必须是支持的简历内容模块</li>
 *   <li>数组元素必须包含 _source 或 _pending 之一(互斥)</li>
 *   <li>Map 类型值若含 _source 和 _pending 则互斥校验</li>
 *   <li>序列化字节数 ≤ max-output-bytes</li>
 *   <li>schemaVersion 不匹配抛 VALIDATION</li>
 * </ol>
 */
@Component
public class JobGenerationSchemaValidator {

    static final String CURRENT_VERSION = "v1.0.0";
    static final Set<String> SUPPORTED_SECTIONS =
            Set.of("basics", "work", "education", "skills", "projects", "certificates",
                    "objective", "volunteering", "courses", "publications", "customSections");

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
        validate(draft, schemaVersion, null);
    }

    public void validate(Map<String, Object> draft, String schemaVersion, Set<Long> allowedMaterialIds) {
        if (!CURRENT_VERSION.equals(schemaVersion)) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "Schema 版本不匹配, 当前: " + CURRENT_VERSION + ", 传入: " + schemaVersion);
        }
        if (draft == null || draft.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "草稿为空");
        }

        // 1. 顶层键校验
        for (String key : draft.keySet()) {
            if (!SUPPORTED_SECTIONS.contains(key)) {
                throw new BusinessException(ErrorCode.VALIDATION, "不允许的顶层字段: " + key);
            }
        }

        // 2. 递归校验 _source/_pending
        for (Map.Entry<String, Object> entry : draft.entrySet()) {
            // basics can be derived from the confirmed personal-profile snapshot.
            // It is not a career material and therefore has no materialId to cite.
            validateValue(entry.getValue(), entry.getKey(), !"basics".equals(entry.getKey()) && !"objective".equals(entry.getKey()), allowedMaterialIds);
        }

        // 3. 字节数校验
        checkByteSize(draft);
    }

    @SuppressWarnings("unchecked")
    private void validateValue(Object value, String path, boolean requiresProvenance, Set<Long> allowedMaterialIds) {
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            boolean hasSource = map.containsKey("_source") || map.containsKey("_sources");
            boolean hasPending = map.containsKey("_pending");
            if (hasSource && hasPending) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        path + ": _source 与 _pending 不能同时存在");
            }
            // 递归校验子节点
            if (requiresProvenance && hasSource == hasPending) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        path + ": must include exactly one of _source, _sources, or _pending");
            }
            if (hasSource && allowedMaterialIds != null && !"basics".equals(path) && !"objective".equals(path)) {
                validateSourceIds(map, path, allowedMaterialIds);
            }
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!entry.getKey().startsWith("_")) {
                    validateValue(entry.getValue(), path + "." + entry.getKey(), false, allowedMaterialIds);
                }
            }
        } else if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item instanceof Map) {
                    Map<String, Object> mapItem = (Map<String, Object>) item;
                    boolean hasSource = mapItem.containsKey("_source") || mapItem.containsKey("_sources");
                    boolean hasPending = mapItem.containsKey("_pending");
                    if (hasSource == hasPending) {
                        throw new BusinessException(ErrorCode.VALIDATION,
                                path + "[" + i + "]: 数组元素必须包含 _source 或 _pending 之一");
                    }
                }
                validateValue(item, path + "[" + i + "]", true, allowedMaterialIds);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateSourceIds(Map<String, Object> map, String path, Set<Long> allowedMaterialIds) {
        Set<Long> sourceIds = new HashSet<>();
        Object sources = map.get("_sources");
        if (sources instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> source)) {
                    throw new BusinessException(ErrorCode.VALIDATION, path + ": _sources entries must be objects");
                }
                addSourceId(((Map<String, Object>) source).get("materialId"), path, sourceIds);
            }
        }
        Object legacy = map.get("_source");
        if (legacy instanceof Map<?, ?> source) {
            addSourceId(((Map<String, Object>) source).get("materialId"), path, sourceIds);
        }
        if (sourceIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, path + ": source metadata must contain a materialId");
        }
        if (!allowedMaterialIds.containsAll(sourceIds)) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    path + ": source is not in the confirmed material snapshot");
        }
    }

    private void addSourceId(Object value, String path, Set<Long> sourceIds) {
        Long id = null;
        if (value instanceof Number number) id = number.longValue();
        else if (value != null) {
            try { id = Long.parseLong(value.toString()); } catch (NumberFormatException ignored) { }
        }
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, path + ": source materialId is required");
        }
        sourceIds.add(id);
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
