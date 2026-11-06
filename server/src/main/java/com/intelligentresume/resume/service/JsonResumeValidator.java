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
 * JSON Resume 结构校验器：写库前的最后一道门，只做"顶层形状 + 体积"级别的校验，不递归校验条目内部。
 *
 * <p>① 业务问题：所有进入版本表的简历内容都是一份 JSON，如果不在门口拦住畸形/超大的 JSON，
 * 坏数据会一直躺在版本历史里。② 链路位置：被 {@code ResumeVersionService#save}/{@code restore}
 * 和 {@code ResumeService#create} 在落库前调用；③ 改坏后果：白名单放宽 → 乱七八糟的章节进库、
 * 导出和前端渲染都可能出现未知键；上限调太大 → 单版本体积膨胀拖慢列表接口。
 *
 * <p>要点：
 * <ul>
 *     <li>顶层 key 是**白名单**（{@code allow-sections}）：多一个键就拒绝，不做静默裁剪——
 *         静默裁剪会让用户以为内容存下了。</li>
 *     <li>必需 key 与白名单均由配置注入，改配置即改契约，前端章节注册表要跟着同步。</li>
 *     <li>大小限制按**序列化后字节数**算（不是字符数），默认 262144 字节 = 256KB
 *         （该默认值未找到明确依据，改前先全仓搜 {@code max-bytes} 及相关测试确认口径）；
 *         超限直接 400。</li>
 *     <li>{@code template} / {@code layout} 在允许列表里，因为版式与内容同存于 {@code resume_json}。</li>
 * </ul>
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

    /**
     * 校验顺序：非空 → 必需顶层字段 → 顶层字段白名单 → 序列化体积。体积检查放最后，
     * 因为要真的序列化一次；前面的检查都是纯内存判断，能更早给出更具体的错误。
     *
     * <p>序列化失败（如值里含不可序列化对象）同样按 400 处理，不会让脏数据落库。
     */
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
