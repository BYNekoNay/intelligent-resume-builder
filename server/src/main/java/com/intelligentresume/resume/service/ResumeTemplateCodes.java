package com.intelligentresume.resume.service;

import java.util.Set;

/**
 * 受支持的简历模板代号白名单，以及"取值兜底"的唯一实现。
 *
 * <p>要点：
 * <ul>
 *     <li>{@link #SUPPORTED} 必须与导出请求的 {@code @Pattern}、PDF 渲染器的样式表一致；
 *         在这里加模板而渲染端没有对应样式，导出会静默落到 classic（渲染器 getOrDefault）。</li>
 *     <li>{@link #normalize} 对**任何**非法输入（null、非字符串、未知代号）都返回 {@link #DEFAULT}，
 *         所以它只用于"读数展示"，不能用来校验用户输入——校验必须用 {@link #SUPPORTED} 判断。</li>
 *     <li>大小写敏感：导出与保存都按原样比对，不做 trim/lowercase。</li>
 * </ul>
 */
public final class ResumeTemplateCodes {
    public static final String DEFAULT = "classic";
    public static final Set<String> SUPPORTED = Set.of(
            DEFAULT, "modern", "minimal", "ats", "executive", "compact", "academic");

    private ResumeTemplateCodes() {
    }

    public static String normalize(Object value) {
        return value instanceof String code && SUPPORTED.contains(code) ? code : DEFAULT;
    }
}
