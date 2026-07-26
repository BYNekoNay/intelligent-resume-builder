package com.intelligentresume.common.observability;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FailureCategoryClassifierTest {

    private final FailureCategoryClassifier classifier = new FailureCategoryClassifier();

    @Test
    void classifiesAiOperationalFailuresWithoutUsingRawMessagesAsLabels() {
        assertEquals(AiFailureCategory.TIMEOUT,
                classifier.ai(new ResourceAccessException("read timed out")));
        assertEquals(AiFailureCategory.RATE_LIMITED,
                classifier.ai(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS)));
        assertEquals(AiFailureCategory.SCHEMA_INVALID,
                classifier.aiMessage("Draft schema validation failed"));
        assertEquals(AiFailureCategory.CONSENT_REVOKED,
                classifier.ai(new BusinessException(ErrorCode.CONSENT_REQUIRED)));
        assertEquals(AiFailureCategory.QUOTA_EXHAUSTED,
                classifier.ai(new BusinessException(ErrorCode.RATE_LIMITED)));
    }

    @Test
    void classifiesPdfOperationalFailures() {
        assertEquals(PdfFailureCategory.CONNECTION,
                classifier.pdf(new ResourceAccessException("connection refused")));
        assertEquals(PdfFailureCategory.AUTH, classifier.pdfMessage("PDF service auth failed"));
        assertEquals(PdfFailureCategory.INPUT_TOO_LARGE, classifier.pdfMessage("input too large"));
        assertEquals(PdfFailureCategory.STORAGE, classifier.pdfMessage("storage write failed"));
    }
}
