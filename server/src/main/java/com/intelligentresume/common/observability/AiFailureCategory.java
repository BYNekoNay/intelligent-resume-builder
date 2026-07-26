package com.intelligentresume.common.observability;

/** Stable, low-cardinality categories for AI operations. */
public enum AiFailureCategory {
    NONE,
    TIMEOUT,
    CONNECTION,
    RATE_LIMITED,
    PROVIDER_4XX,
    PROVIDER_5XX,
    PROVIDER_RESPONSE_INVALID,
    SELECTION_INVALID,
    SCHEMA_INVALID,
    CONSENT_REVOKED,
    QUOTA_EXHAUSTED,
    INTERNAL
}
