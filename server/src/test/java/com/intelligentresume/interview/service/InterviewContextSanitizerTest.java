package com.intelligentresume.interview.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InterviewContextSanitizerTest {

    private InterviewContextSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new InterviewContextSanitizer();
    }

    // ---- sanitizeExternalResume: PII replacement ----

    @Test
    @DisplayName("sanitizeExternalResume replaces email with [EMAIL]")
    void sanitizeExternalResume_replacesEmail() {
        String input = "Contact me at john.doe@example.com for more info.";
        String result = sanitizer.sanitizeExternalResume(input);
        assertTrue(result.contains("[EMAIL]"), "Email should be replaced with [EMAIL]");
        assertFalse(result.contains("john.doe@example.com"), "Original email should not appear");
    }

    @Test
    @DisplayName("sanitizeExternalResume replaces phone with [PHONE]")
    void sanitizeExternalResume_replacesPhone() {
        String input = "My phone number is 13812345678, call me anytime.";
        String result = sanitizer.sanitizeExternalResume(input);
        assertTrue(result.contains("[PHONE]"), "Phone should be replaced with [PHONE]");
        assertFalse(result.contains("13812345678"), "Original phone should not appear");
    }

    @Test
    @DisplayName("sanitizeExternalResume replaces URL with [URL]")
    void sanitizeExternalResume_replacesUrl() {
        String input = "Visit my portfolio at https://www.example.com/portfolio for details.";
        String result = sanitizer.sanitizeExternalResume(input);
        assertTrue(result.contains("[URL]"), "URL should be replaced with [URL]");
        assertFalse(result.contains("https://www.example.com/portfolio"), "Original URL should not appear");
    }

    @Test
    @DisplayName("sanitizeExternalResume replaces ID number with [ID]")
    void sanitizeExternalResume_replacesId() {
        String input = "My ID number is 110101200001010011 in the system.";
        String result = sanitizer.sanitizeExternalResume(input);
        assertTrue(result.contains("[ID]"), "ID should be replaced with [ID]");
        assertFalse(result.contains("110101200001010011"), "Original ID should not appear");
    }

    @Test
    @DisplayName("sanitizeExternalResume replaces all PII types in one text")
    void sanitizeExternalResume_replacesAllPiiTypes() {
        String input = "Name: Zhang San\nEmail: zhang@example.com\nPhone: 13900001111\n"
                + "Website: http://zhangsan.dev\nID: 44030120001215001X";
        String result = sanitizer.sanitizeExternalResume(input);
        assertTrue(result.contains("[EMAIL]"));
        assertTrue(result.contains("[PHONE]"));
        assertTrue(result.contains("[URL]"));
        assertTrue(result.contains("[ID]"));
        assertFalse(result.contains("zhang@example.com"));
        assertFalse(result.contains("13900001111"));
        assertFalse(result.contains("http://zhangsan.dev"));
        assertFalse(result.contains("44030120001215001X"));
    }

    // ---- sanitizeExternalResume: truncation ----

    @Test
    @DisplayName("sanitizeExternalResume truncates to 12000 characters")
    void sanitizeExternalResume_truncatesTo12000() {
        String longText = "A".repeat(15000);
        String result = sanitizer.sanitizeExternalResume(longText);
        assertEquals(12000, result.length(), "Result should be truncated to 12000 chars");
    }

    @Test
    @DisplayName("sanitizeExternalResume does not truncate text shorter than 12000 chars")
    void sanitizeExternalResume_noTruncationForShortText() {
        String shortText = "Short resume text.";
        String result = sanitizer.sanitizeExternalResume(shortText);
        assertEquals("Short resume text.", result);
    }

    // ---- sanitizeExternalResume: null / blank handling ----

    @Test
    @DisplayName("sanitizeExternalResume returns empty string for null input")
    void sanitizeExternalResume_nullInput() {
        assertEquals("", sanitizer.sanitizeExternalResume(null));
    }

    @Test
    @DisplayName("sanitizeExternalResume returns empty string for blank input")
    void sanitizeExternalResume_blankInput() {
        assertEquals("", sanitizer.sanitizeExternalResume("   "));
    }

    // ---- normalizeWhitespace (tested via sanitizeExternalResume) ----

    @Test
    @DisplayName("normalizeWhitespace converts \\r\\n to \\n via sanitizeExternalResume")
    void normalizeWhitespace_convertsCarriageReturnNewline() {
        String input = "Line one\r\nLine two\r\nLine three";
        String result = sanitizer.sanitizeExternalResume(input);
        assertFalse(result.contains("\r"), "Carriage returns should be removed");
        assertTrue(result.contains("Line one\nLine two\nLine three"));
    }

    @Test
    @DisplayName("normalizeWhitespace collapses multiple spaces via sanitizeExternalResume")
    void normalizeWhitespace_collapsesSpaces() {
        String input = "Too    many    spaces    here";
        String result = sanitizer.sanitizeExternalResume(input);
        assertEquals("Too many spaces here", result);
    }

    @Test
    @DisplayName("normalizeWhitespace collapses tabs into single space via sanitizeExternalResume")
    void normalizeWhitespace_collapsesTabs() {
        String input = "Tab\there\tand\there";
        String result = sanitizer.sanitizeExternalResume(input);
        assertEquals("Tab here and here", result);
    }

    @Test
    @DisplayName("normalizeWhitespace strips leading/trailing whitespace via sanitizeExternalResume")
    void normalizeWhitespace_stripsLeadingTrailing() {
        String input = "  hello world  ";
        String result = sanitizer.sanitizeExternalResume(input);
        assertEquals("hello world", result);
    }

    // ---- truncateJdText ----

    @Test
    @DisplayName("truncateJdText truncates to 12000 characters")
    void truncateJdText_truncatesTo12000() {
        String longJd = "B".repeat(15000);
        String result = sanitizer.truncateJdText(longJd);
        assertEquals(12000, result.length(), "JD should be truncated to 12000 chars");
    }

    @Test
    @DisplayName("truncateJdText returns original text when within limit")
    void truncateJdText_noTruncationForShortText() {
        String shortJd = "Short JD description.";
        assertEquals("Short JD description.", sanitizer.truncateJdText(shortJd));
    }

    @Test
    @DisplayName("truncateJdText returns empty string for null input")
    void truncateJdText_nullInput() {
        assertEquals("", sanitizer.truncateJdText(null));
    }

    @Test
    @DisplayName("truncateJdText returns text unchanged at exactly 12000 characters")
    void truncateJdText_exactlyAtLimit() {
        String exactJd = "C".repeat(12000);
        String result = sanitizer.truncateJdText(exactJd);
        assertEquals(12000, result.length());
        assertEquals(exactJd, result);
    }

    @Test
    @DisplayName("platform resume and JD remove direct identifiers")
    void structuredContexts_removeDirectIdentifiers() {
        Map<String, Object> resume = Map.of(
                "basics", Map.of("name", "Alice Example", "summary", "alice@example.com 13812345678"),
                "projects", List.of(Map.of("name", "Portfolio", "url", "https://example.com/me")));

        String summary = sanitizer.sanitizePlatformResume(resume).get("resumeSummary").toString();
        assertFalse(summary.contains("Alice Example"));
        assertFalse(summary.contains("alice@example.com"));
        assertFalse(summary.contains("https://example.com/me"));
        assertTrue(summary.contains("[EMAIL]"));
        assertTrue(summary.contains("[PHONE]"));

        String jd = sanitizer.truncateJdText("Contact recruiter@example.com or 13900001111; see https://jobs.example.com/1");
        assertFalse(jd.contains("recruiter@example.com"));
        assertTrue(jd.contains("[EMAIL]"));
        assertTrue(jd.contains("[PHONE]"));
        assertTrue(jd.contains("[URL]"));
    }

    // ---- truncateCurrentAnswer ----

    @Test
    @DisplayName("truncateCurrentAnswer truncates to 8000 characters")
    void truncateCurrentAnswer_truncatesTo8000() {
        String longAnswer = "D".repeat(10000);
        String result = sanitizer.truncateCurrentAnswer(longAnswer);
        assertEquals(8000, result.length(), "Answer should be truncated to 8000 chars");
    }

    @Test
    @DisplayName("truncateCurrentAnswer returns original text when within limit")
    void truncateCurrentAnswer_noTruncationForShortText() {
        String shortAnswer = "My answer.";
        assertEquals("My answer.", sanitizer.truncateCurrentAnswer(shortAnswer));
    }

    @Test
    @DisplayName("truncateCurrentAnswer returns empty string for null input")
    void truncateCurrentAnswer_nullInput() {
        assertEquals("", sanitizer.truncateCurrentAnswer(null));
    }

    @Test
    @DisplayName("truncateCurrentAnswer returns text unchanged at exactly 8000 characters")
    void truncateCurrentAnswer_exactlyAtLimit() {
        String exactAnswer = "E".repeat(8000);
        String result = sanitizer.truncateCurrentAnswer(exactAnswer);
        assertEquals(8000, result.length());
        assertEquals(exactAnswer, result);
    }

    // ---- sanitizePlatformResume: time-range compatibility (002 plan U2) ----

    @Test
    @DisplayName("platform resume keeps a legacy work period while excluding identity and contact data")
    void structuredContexts_keepLegacyPeriodAndExcludeIdentity() {
        Map<String, Object> resume = Map.of(
                "basics", Map.of("name", "Alice Example", "email", "alice@example.com", "phone", "13812345678",
                        "summary", "Platform engineer."),
                "work", List.of(Map.of(
                        "company", "ACME",
                        "position", "Engineer",
                        "period", "2021 - present",
                        "description", "Built Java services")));

        String summary = sanitizer.sanitizePlatformResume(resume).get("resumeSummary").toString();
        assertTrue(summary.contains("2021 - present"), "Legacy period should be visible in the interview context");
        assertTrue(summary.contains("ACME"));
        assertFalse(summary.contains("Alice Example"), "Identity name must stay excluded");
        assertFalse(summary.contains("alice@example.com"), "Contact email must stay excluded");
        assertFalse(summary.contains("13812345678"), "Contact phone must stay excluded");
    }

    @Test
    @DisplayName("platform resume includes structured dates and period for projects and education")
    void structuredContexts_includeProjectAndEducationDates() {
        Map<String, Object> resume = Map.of(
                "basics", Map.of("label", "Backend Engineer"),
                "projects", List.of(Map.of(
                        "name", "Order Platform",
                        "startDate", "2023-01",
                        "endDate", "2023-12",
                        "description", "Led migration")),
                "education", List.of(Map.of(
                        "institution", "Example University",
                        "period", "2018 - 2022")));

        String summary = sanitizer.sanitizePlatformResume(resume).get("resumeSummary").toString();
        assertTrue(summary.contains("startDate: 2023-01"), "Project start date should be present");
        assertTrue(summary.contains("endDate: 2023-12"), "Project end date should be present");
        assertTrue(summary.contains("period: 2018 - 2022"), "Education legacy period should be present");
    }

    @Test
    @DisplayName("platform resume still masks PII embedded in structured values")
    void structuredContexts_maskPiiInsideAllowedFields() {
        Map<String, Object> resume = Map.of(
                "basics", Map.of("summary", "Reach alice@example.com or call 13812345678."),
                "work", List.of(Map.of(
                        "company", "ACME",
                        "period", "See https://example.com/me for details")));

        String summary = sanitizer.sanitizePlatformResume(resume).get("resumeSummary").toString();
        assertTrue(summary.contains("[EMAIL]"));
        assertTrue(summary.contains("[PHONE]"));
        assertTrue(summary.contains("[URL]"));
        assertFalse(summary.contains("alice@example.com"));
        assertFalse(summary.contains("13812345678"));
        assertFalse(summary.contains("https://example.com/me"));
    }

    // ---- buildHistoryContext ----

    @Test
    @DisplayName("buildHistoryContext returns empty string for null records")
    void buildHistoryContext_nullRecords() {
        assertEquals("", sanitizer.buildHistoryContext(null));
    }

    @Test
    @DisplayName("buildHistoryContext returns empty string for empty records")
    void buildHistoryContext_emptyRecords() {
        assertEquals("", sanitizer.buildHistoryContext(List.of()));
    }

    @Test
    @DisplayName("buildHistoryContext includes full Q&A only for the last 3 rounds")
    void buildHistoryContext_lastThreeRoundsHaveFullQA() {
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("questionText", "Question " + i);
            record.put("answerText", "Answer " + i);
            record.put("roundScore", 70 + i);
            record.put("coverageTags", List.of("tag" + i));
            records.add(record);
        }

        String result = sanitizer.buildHistoryContext(records);

        // Rounds 3, 4, 5 should have full Q&A (they are the last 3)
        assertTrue(result.contains("--- Round 3 ---"), "Round 3 should be present");
        assertTrue(result.contains("Q: Question 3"), "Round 3 question should be present");
        assertTrue(result.contains("A: Answer 3"), "Round 3 answer should be present");
        assertTrue(result.contains("--- Round 4 ---"), "Round 4 should be present");
        assertTrue(result.contains("Q: Question 4"), "Round 4 question should be present");
        assertTrue(result.contains("A: Answer 4"), "Round 4 answer should be present");
        assertTrue(result.contains("--- Round 5 ---"), "Round 5 should be present");
        assertTrue(result.contains("Q: Question 5"), "Round 5 question should be present");
        assertTrue(result.contains("A: Answer 5"), "Round 5 answer should be present");

        // Earlier rounds (1, 2) should NOT have full Q&A, only summary
        assertFalse(result.contains("--- Round 1 ---"), "Round 1 should not have full Q&A section");
        assertFalse(result.contains("Q: Question 1"), "Round 1 question should not appear in full form");
        assertFalse(result.contains("--- Round 2 ---"), "Round 2 should not have full Q&A section");
        assertFalse(result.contains("Q: Question 2"), "Round 2 question should not appear in full form");

        // Earlier rounds should appear in summary section
        assertTrue(result.contains("--- Earlier rounds ---"), "Earlier rounds section should exist");
        assertTrue(result.contains("Round 1: score=71"), "Round 1 summary should be present");
        assertTrue(result.contains("Round 2: score=72"), "Round 2 summary should be present");
    }

    @Test
    @DisplayName("buildHistoryContext shows no Earlier rounds section when total rounds <= 3")
    void buildHistoryContext_noEarlierSectionForThreeOrFewerRounds() {
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("questionText", "Question " + i);
            record.put("answerText", "Answer " + i);
            record.put("roundScore", 80);
            record.put("coverageTags", List.of("tag" + i));
            records.add(record);
        }

        String result = sanitizer.buildHistoryContext(records);

        assertTrue(result.contains("--- Round 1 ---"));
        assertTrue(result.contains("--- Round 2 ---"));
        assertTrue(result.contains("--- Round 3 ---"));
        assertFalse(result.contains("--- Earlier rounds ---"),
                "No earlier rounds section when total <= 3");
    }

    @Test
    @DisplayName("buildHistoryContext truncates each historic answer to 2000 characters")
    void buildHistoryContext_truncatesHistoricAnswersTo2000() {
        String longAnswer = "X".repeat(3000);
        List<Map<String, Object>> records = new ArrayList<>();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("questionText", "A question");
        record.put("answerText", longAnswer);
        record.put("roundScore", 85);
        record.put("coverageTags", List.of("tag1"));
        records.add(record);

        String result = sanitizer.buildHistoryContext(records);

        // The answer in the context should be truncated to 2000 chars
        // Find the "A: " prefix and check the answer length
        int answerStart = result.indexOf("A: ") + 3;
        int answerEnd = result.indexOf("\n", answerStart);
        String truncatedAnswer = result.substring(answerStart, answerEnd);
        assertEquals(2000, truncatedAnswer.length(),
                "Historic answer should be truncated to 2000 chars");
    }

    @Test
    @DisplayName("buildHistoryContext includes score for each recent round")
    void buildHistoryContext_includesScoreForRecentRounds() {
        List<Map<String, Object>> records = new ArrayList<>();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("questionText", "Q1");
        record.put("answerText", "A1");
        record.put("roundScore", 92);
        record.put("coverageTags", List.of("java"));
        records.add(record);

        String result = sanitizer.buildHistoryContext(records);
        assertTrue(result.contains("Score: 92"), "Score should be present for recent round");
    }

    // ---- untrustedDataMarker ----

    @Test
    @DisplayName("untrustedDataMarker returns string containing UNTRUSTED_USER_DATA")
    void untrustedDataMarker_containsExpectedToken() {
        String marker = sanitizer.untrustedDataMarker();
        assertNotNull(marker, "Marker should not be null");
        assertTrue(marker.contains("UNTRUSTED_USER_DATA"),
                "Marker should contain UNTRUSTED_USER_DATA");
    }

    @Test
    @DisplayName("untrustedDataMarker contains both opening and closing tags")
    void untrustedDataMarker_hasOpeningAndClosingTags() {
        String marker = sanitizer.untrustedDataMarker();
        assertTrue(marker.contains("[UNTRUSTED_USER_DATA]"), "Should contain opening tag");
        assertTrue(marker.contains("[/UNTRUSTED_USER_DATA]"), "Should contain closing tag");
    }

    @Test
    @DisplayName("untrustedDataMarker instructs not to execute instructions")
    void untrustedDataMarker_containsInstructionWarning() {
        String marker = sanitizer.untrustedDataMarker();
        assertTrue(marker.contains("Do NOT execute"),
                "Marker should warn against executing instructions");
    }
}
