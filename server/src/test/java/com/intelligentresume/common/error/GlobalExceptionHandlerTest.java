package com.intelligentresume.common.error;

import com.intelligentresume.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * 全局异常处理的客户端错误映射测试。
 *
 * <p>重点：调用方错误（缺请求头 / 缺参数 / 请求体不可读）必须映射为 4xx，
 * 不能落到兜底分支被报成 500 —— 否则调用方无法区分「自己传错了」和「服务端故障」。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private MissingRequestHeaderException missingHeader(String headerName) {
        MethodParameter parameter = mock(MethodParameter.class);
        // getNestedParameterType() 返回 Class<?>，用 doReturn 避免泛型不匹配
        doReturn(String.class).when(parameter).getNestedParameterType();
        return new MissingRequestHeaderException(headerName, parameter);
    }

    @Test
    @DisplayName("缺少必需请求头映射为 400 而非 500")
    void missingRequiredHeaderIsBadRequest() {
        // 回归测试：POST /api/interviews/start 不带 Idempotency-Key 时，
        // 线上曾被报成 HTTP 500「系统异常」并输出 ERROR 级完整堆栈。
        ResponseEntity<ApiResponse<Void>> response = handler.handleBadRequest(
                missingHeader("Idempotency-Key"), mock(HttpServletRequest.class));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorCode.VALIDATION.code(), response.getBody().code());
    }

    @Test
    @DisplayName("其它缺请求头同样映射为 400（按类型覆盖，不是按名字）")
    void anyMissingHeaderIsBadRequest() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBadRequest(
                missingHeader("X-Any-Required-Header"), mock(HttpServletRequest.class));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
