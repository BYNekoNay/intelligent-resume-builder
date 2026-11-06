package com.intelligentresume.common.api;

import java.util.UUID;

/**
 * 全站统一响应信封：{@code {code, message, data, traceId}}。
 *
 * <p>【小白导览】所有接口返回给前端的「标准快递盒」：code 是业务状态码（0=成功，非 0 看 ErrorCode），
 * message 是给用户看的中文文案，data 是业务数据，traceId 是这次请求的追踪号（排查问题时
 * 前后端对账用）。前端只认这个盒子的结构，改坏字段名 = 全站接口解析失败。
 *
 * <p>不变量：
 * <ul>
 *   <li>{@code code == 0} 才表示成功；失败时 {@code data} 恒为 null，业务码见 {@link com.intelligentresume.common.error.ErrorCode}。</li>
 *   <li>HTTP 状态与业务码是两套东西：新增端点请返回语义正确的 HTTP 状态，信封内仍带业务码；
 *       成功时一律走 {@link #success}。</li>
 *   <li>{@code traceId} 必须与响应头 {@code X-Trace-Id} 一致，用于把客户端报错对到服务端日志；
 *       取不到时兜底生成一个，保证字段永不为 null。</li>
 * </ul>
 */
public record ApiResponse<T>(int code, String message, T data, String traceId) {

    /** 成功响应：code 固定 0、message 固定 "success"。 */
    public static <T> ApiResponse<T> success(T data, String traceId) {
        return new ApiResponse<>(0, "success", data, resolveTraceId(traceId));
    }

    /** 失败响应：data 恒为 null，message 应是可直接展示给用户的中文文案（上游原文需先经 PublicFailureCopy）。 */
    public static <T> ApiResponse<T> failure(int code, String message, String traceId) {
        return new ApiResponse<>(code, message, null, resolveTraceId(traceId));
    }

    /** traceId 兜底：过滤器未运行时（如单测直调）也要给出一个非空值，避免前端读到 null。 */
    private static String resolveTraceId(String traceId) {
        return traceId == null ? UUID.randomUUID().toString() : traceId;
    }
}
