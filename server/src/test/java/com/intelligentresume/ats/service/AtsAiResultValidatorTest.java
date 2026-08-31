package com.intelligentresume.ats.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentresume.ats.dto.AtsAiInsights;
import com.intelligentresume.ats.dto.AtsFallbackCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AtsAiResultValidatorTest {
    private AtsAiResultValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AtsAiResultValidator(new ObjectMapper());
    }

    @Test
    void acceptsTheStrictContractAndDropsFabricatedQuotes() {
        Map<String, Object> output = validOutput("not present in the resume");

        AtsAiInsights result = validator.validate(output,
                Map.of("work", List.of(Map.of("description", "Built Java services"))));

        assertEquals("MEDIUM", result.confidence());
        assertNull(result.evidenceFindings().get(0).quote());
        assertEquals("MATCHED", result.semanticCoverage().get(0).status());
    }

    @Test
    void rejectsScoresAndOtherUnexpectedFields() {
        Map<String, Object> output = new java.util.LinkedHashMap<>(validOutput("Built Java services"));
        output.put("totalScore", 98);

        AtsAiAnalysisException error = assertThrows(AtsAiAnalysisException.class,
                () -> validator.validate(output, Map.of("summary", "Built Java services")));

        assertEquals(AtsFallbackCode.INVALID_RESPONSE, error.fallbackCode());
    }

    @Test
    void rejectsInvalidEnums() {
        Map<String, Object> output = new java.util.LinkedHashMap<>(validOutput(null));
        output.put("confidence", "CERTAIN");

        assertThrows(AtsAiAnalysisException.class, () -> validator.validate(output, Map.of()));
    }

    @Test
    void normalizesEmptyOptionalEvidenceAndRiskEntries() {
        Map<String, Object> output = new java.util.LinkedHashMap<>(validOutput(""));
        output.put("readabilityRisks", java.util.Arrays.asList(null, "", "Keep headings conventional"));
        Map<String, Object> coverage = new java.util.LinkedHashMap<>(
                ((List<Map<String, Object>>) output.get("semanticCoverage")).get(0));
        coverage.put("evidence", "");
        output.put("semanticCoverage", List.of(coverage));

        AtsAiInsights result = validator.validate(output, Map.of());

        assertNull(result.semanticCoverage().get(0).evidence());
        assertNull(result.evidenceFindings().get(0).quote());
        assertEquals(List.of("Keep headings conventional"), result.readabilityRisks());
    }

    @Test
    void trimsAndTruncatesOverlongNarrativeText() {
        Map<String, Object> output = new java.util.LinkedHashMap<>(validOutput(null));
        output.put("summary", "  " + "s".repeat(510) + "  ");
        output.put("readabilityRisks", List.of("  " + "r".repeat(510) + "  "));

        AtsAiInsights result = validator.validate(output, Map.of());

        assertEquals(500, result.summary().length());
        assertEquals("s".repeat(500), result.summary());
        assertEquals(500, result.readabilityRisks().get(0).length());
        assertEquals("r".repeat(500), result.readabilityRisks().get(0));
    }

    @Test
    void stillRejectsBlankRequiredText() {
        Map<String, Object> output = new java.util.LinkedHashMap<>(validOutput(null));
        output.put("summary", "   ");

        assertThrows(AtsAiAnalysisException.class, () -> validator.validate(output, Map.of()));
    }

    @Test
    void rejectsObjectEntriesInReadabilityRisks() {
        Map<String, Object> output = new java.util.LinkedHashMap<>(validOutput(null));
        output.put("readabilityRisks", List.of(Map.of("risk", "Dense paragraph")));

        assertThrows(AtsAiAnalysisException.class, () -> validator.validate(output, Map.of()));
    }

    private Map<String, Object> validOutput(String quote) {
        return Map.of(
                "summary", "The resume covers the main backend requirement.",
                "semanticCoverage", List.of(Map.of(
                        "requirement", "Java", "status", "MATCHED", "evidence", "Built Java services", "reason", "Direct evidence")),
                "evidenceFindings", List.of(nullableMap(
                        "section", "work", "quote", quote, "assessment", "Specific", "suggestion", "Add scale when verified")),
                "readabilityRisks", List.of("Use conventional section headings"),
                "prioritizedActions", List.of(Map.of(
                        "priority", "P1", "section", "work", "action", "Add verified scale", "basis", "Evidence quality")),
                "confidence", "MEDIUM");
    }

    private Map<String, Object> nullableMap(Object... values) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }
}
