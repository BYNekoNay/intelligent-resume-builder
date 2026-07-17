package com.intelligentresume.resume.validation;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * JSON Resume 顶层结构校验。
 *
 * <p>骨架:T03 落地完整字段约束;此处仅校验 basics 存在。
 */
@Component
public class JsonResumeValidator {

    private static final Set<String> ALLOWED_TOP_SECTIONS = Set.of(
            "basics", "work", "education", "skills", "projects", "certificates", "languages", "interests", "template"
    );

    public void validate(Map<String, Object> resumeJson) {
        if (resumeJson == null || resumeJson.get("basics") == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "resume.json.basics 不能为空");
        }
        boolean hasUnknownSection = resumeJson.keySet().stream()
                .anyMatch(section -> !ALLOWED_TOP_SECTIONS.contains(section));
        if (hasUnknownSection) {
            throw new BusinessException(ErrorCode.VALIDATION, "resume.json 包含不支持的顶层字段");
        }
    }
}
