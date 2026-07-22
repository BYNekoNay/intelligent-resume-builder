package com.intelligentresume.ai.generation.validator;

import com.intelligentresume.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobGenerationSchemaValidatorTest {

    private final JobGenerationSchemaValidator validator = new JobGenerationSchemaValidator();

    @Test
    void acceptsItemsLinkedToNumericCareerMaterials() {
        assertThatCode(() -> validator.validate(resultWithSource("material:101"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsJobKeywordsDisguisedAsMaterialSources() {
        assertThatThrownBy(() -> validator.validate(resultWithSource("material:PARSED_KEYWORDS")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsNestedGeneratedFactsWithoutProvenance() {
        Map<String, Object> work = Map.of("company", "Example", "highlights", List.of(Map.of("text", "Invented metric")),
                "_source", "material:101", "_pending", false);
        Map<String, Object> result = Map.of("draftResumeJson", Map.of("basics", Map.of(), "work", List.of(work),
                "education", List.of(), "skills", List.of(), "projects", List.of()));

        assertThatThrownBy(() -> validator.validate(result)).isInstanceOf(BusinessException.class);
    }

    private Map<String, Object> resultWithSource(String source) {
        return Map.of("draftResumeJson", Map.of("basics", Map.of(), "work", List.of(), "education", List.of(),
                "skills", List.of(Map.of("name", "Java", "_source", source, "_pending", false)), "projects", List.of()));
    }
}
