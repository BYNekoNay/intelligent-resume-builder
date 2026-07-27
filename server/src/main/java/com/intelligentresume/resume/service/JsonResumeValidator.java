package com.intelligentresume.resume.service;

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
 * JSON Resume 结构校验器。
 *
 * <p>校验规则（MVP 级别，不递归校验内部结构）：
 * <ol>
 *     <li>resumeJson 不能为空</li>
 *     <li>顶层必须包含 {@code require-top-level} 中列出的 key（默认 "basics"）</li>
 *     <li>序列化后字节数不超过 {@code max-bytes}（默认 256 KB）</li>
 *     <li>顶层 key 必须在 {@code allow-sections} 中</li>
 * </ol>
 */
@Component
public class JsonResumeValidator {

    private final int maxBytes;
    private final Set<String> requireTopLevel;
    private final Set<String> allowSections;
    private final ObjectMapper objectMapper;

    public JsonResumeValidator(
            @Value("${app.resume.json-schema.max-bytes:262144}") int maxBytes,
            @Value("${app.resume.json-schema.require-top-level:basics}") List<String> requireTopLevel,
            @Value("${app.resume.json-schema.allow-sections:basics,work,education,skills,projects,certificates,languages,awards,objective,links,volunteering,courses,publications,customSections,template,layout}") List<String> allowSections,
            ObjectMapper objectMapper) {
        this.maxBytes = maxBytes;
        this.requireTopLevel = Set.copyOf(requireTopLevel);
        this.allowSections = Set.copyOf(allowSections);
        this.objectMapper = objectMapper;
    }

    public void validate(Map<String, Object> resumeJson) {
        if (resumeJson == null || resumeJson.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "简历 JSON 不能为空");
        }

        for (String required : requireTopLevel) {
            if (!resumeJson.containsKey(required)) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "简历 JSON 缺少必需的顶层字段: " + required);
            }
        }

        for (String key : resumeJson.keySet()) {
            if (!allowSections.contains(key)) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "简历 JSON 包含不允许的顶层字段: " + key);
            }
        }

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(resumeJson);
            if (bytes.length > maxBytes) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "简历 JSON 超过大小限制 (" + maxBytes + " bytes)");
            }
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "简历 JSON 序列化失败");
        }
    }
}
