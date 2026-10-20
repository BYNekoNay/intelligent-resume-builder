package com.intelligentresume.application.domain;

public enum ApplicationStatus {
    /** Preparing; the application has not been submitted yet. */
    DRAFT,
    APPLIED,
    SCREENING,
    INTERVIEWING,
    OFFERED,
    HIRED,
    REJECTED,
    WITHDRAWN,
    NO_RESPONSE
}
