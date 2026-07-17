package com.intelligentresume.common.api;

import java.util.UUID;

public record ApiResponse<T>(int code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> success(T data, String traceId) {
        return new ApiResponse<>(0, "success", data, resolveTraceId(traceId));
    }

    public static <T> ApiResponse<T> failure(int code, String message, String traceId) {
        return new ApiResponse<>(code, message, null, resolveTraceId(traceId));
    }

    private static String resolveTraceId(String traceId) {
        return traceId == null ? UUID.randomUUID().toString() : traceId;
    }
}
