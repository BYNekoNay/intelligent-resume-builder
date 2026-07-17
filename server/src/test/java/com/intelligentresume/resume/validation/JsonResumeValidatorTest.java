package com.intelligentresume.resume.validation;

import com.intelligentresume.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonResumeValidatorTest {

    private final JsonResumeValidator validator = new JsonResumeValidator();

    @Test
    void acceptsAllowedTopLevelSections() {
        assertDoesNotThrow(() -> validator.validate(Map.of(
                "basics", Map.of("name", "Alice"),
                "work", java.util.List.of(),
                "skills", java.util.List.of(),
                "template", Map.of("code", "modern")
        )));
    }

    @Test
    void rejectsUnknownTopLevelSection() {
        assertThrows(BusinessException.class, () -> validator.validate(Map.of(
                "basics", Map.of("name", "Alice"),
                "unknown", Map.of()
        )));
    }
}
