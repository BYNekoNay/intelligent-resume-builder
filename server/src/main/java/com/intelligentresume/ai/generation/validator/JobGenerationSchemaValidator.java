package com.intelligentresume.ai.generation.validator;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 校验 AI 返回的草稿结构必须:
 * <ol>
 *     <li>顶层 {@code draftResumeJson} 必须为 object</li>
 *     <li>basics / work / education / skills / projects 顶层节点必须为 array 或 object</li>
 *     <li>每条用户可见条目(数组元素)必须含 {@code _source} 字符串与 {@code _pending} 布尔值</li>
 * </ol>
 */
@Component
public class JobGenerationSchemaValidator {

    private static final List<String> TOP_ARRAY_FIELDS = List.of(
            "work", "education", "skills", "projects");

    public void validate(Map<String, Object> resultJson) {
        if (resultJson == null) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "AI 返回为空");
        }
        Object draft = resultJson.get("draftResumeJson");
        if (!(draft instanceof Map<?, ?> draftMap)) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "AI 返回缺少 draftResumeJson 对象");
        }

        Object basics = draftMap.get("basics");
        if (basics != null && !(basics instanceof Map<?, ?>)) {
            throw new BusinessException(ErrorCode.AI_FAILURE, "draftResumeJson.basics 必须是对象");
        }

        for (String field : TOP_ARRAY_FIELDS) {
            Object value = draftMap.get(field);
            if (value == null) continue;
            if (!(value instanceof List<?> list)) {
                throw new BusinessException(ErrorCode.AI_FAILURE,
                        "draftResumeJson." + field + " 必须是数组");
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> entry)) {
                    throw new BusinessException(ErrorCode.AI_FAILURE,
                            "draftResumeJson." + field + "[] 必须是对象");
                }
                validateNestedEntries(entry, field);
                if (!entry.containsKey("_source")) {
                    throw new BusinessException(ErrorCode.AI_FAILURE,
                            field + " 条目缺少 _source 字段");
                }
                Object source = entry.get("_source");
                if (!(source instanceof String sourceText) || !sourceText.matches("material:[1-9]\\d*")) {
                    throw new BusinessException(ErrorCode.AI_FAILURE,
                            field + " item must reference a numeric career material source");
                }
                if (!entry.containsKey("_pending")) {
                    throw new BusinessException(ErrorCode.AI_FAILURE,
                            field + " 条目缺少 _pending 字段");
                }
            }
        }
    }

    private void validateNestedEntries(Map<?, ?> entry, String label) {
        for (Map.Entry<?, ?> child : entry.entrySet()) {
            if (child.getKey() instanceof String key && !key.startsWith("_")) {
                validateNestedValue(child.getValue(), label + "." + key);
            }
        }
    }

    private void validateNestedValue(Object value, String label) {
        if (value instanceof Map<?, ?> nested) {
            if (!(nested.get("_source") instanceof String source) || !source.matches("material:[1-9]\\d*")
                    || !(nested.get("_pending") instanceof Boolean)) {
                throw new BusinessException(ErrorCode.AI_FAILURE, label + " nested item must include provenance and pending state");
            }
            validateNestedEntries(nested, label);
        } else if (value instanceof List<?> list) {
            for (Object child : list) validateNestedValue(child, label + "[]");
        }
    }
}
