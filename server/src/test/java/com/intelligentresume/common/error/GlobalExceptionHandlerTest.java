package com.intelligentresume.common.error;

import com.intelligentresume.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.List;

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

    @Test
    @DisplayName("方法不允许映射为 405 信封（保留协议语义，不落 500 兜底）")
    void methodNotAllowedIs405() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleHttpSemantics(
                new HttpRequestMethodNotSupportedException("GET"), mock(HttpServletRequest.class));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorCode.VALIDATION.code(), response.getBody().code());
        assertEquals("请求方法不受支持", response.getBody().message());
    }

    @Test
    @DisplayName("不支持的请求媒体类型映射为 415")
    void unsupportedMediaTypeIs415() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleHttpSemantics(
                new HttpMediaTypeNotSupportedException("Unsupported '" + MediaType.TEXT_PLAIN + "'"), mock(HttpServletRequest.class));

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorCode.VALIDATION.code(), response.getBody().code());
        assertEquals("不支持的媒体类型", response.getBody().message());
    }

    @Test
    @DisplayName("不可接受的响应媒体类型映射为 406")
    void notAcceptableIs406() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleHttpSemantics(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_XML)), mock(HttpServletRequest.class));

        assertEquals(HttpStatus.NOT_ACCEPTABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("不支持的响应媒体类型", response.getBody().message());
    }

    @Test
    @DisplayName("超限上传映射为 413 信封（不落 500 兜底）")
    void oversizedUploadIs413() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleUploadTooLarge(
                new MaxUploadSizeExceededException(5 * 1024 * 1024), mock(HttpServletRequest.class));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorCode.VALIDATION.code(), response.getBody().code());
        assertEquals("上传文件超出大小限制", response.getBody().message());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    }

    @Test
    @DisplayName("非法 multipart 表单映射为 400（不落 500 兜底）")
    void malformedMultipartIs400() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleMultipart(
                new MultipartException("Malformed multipart request"), mock(HttpServletRequest.class));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("上传表单不合法", response.getBody().message());
    }
}
