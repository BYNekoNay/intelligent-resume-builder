package com.intelligentresume.resume.service;

import java.util.Set;

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
