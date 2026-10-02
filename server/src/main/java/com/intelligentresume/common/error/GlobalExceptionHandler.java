package com.intelligentresume.common.error;

import com.intelligentresume.common.api.ApiResponse;
import com.intelligentresume.common.api.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final PublicFailureCopy publicFailureCopy;

    public GlobalExceptionHandler(PublicFailureCopy publicFailureCopy) {
        this.publicFailureCopy = publicFailureCopy;
    }

    /**
     * 业务异常的响应：**公开文案经 {@link PublicFailureCopy} 过一遍**。
     *
     * <p>AI / PDF 两条链路的 {@code BusinessException} 消息常夹带上游细节
     * （如 {@code "Draft schema validation failed: ..."}、{@code "Resume generation failed: ..."}），
     * 直接回给客户端会外泄实现信息、且文案随上游措辞漂移。故对这两个码只回**类别化的稳定文案**，
     * 原文改写进日志（带 traceId）供排查。其余业务码的 message 本就是项目自己的中文用户文案，原样保留。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException exception, HttpServletRequest request) {
        ErrorCode errorCode = exception.getErrorCode();
        String traceId = traceId(request);
        if (publicFailureCopy.shouldLogRawMessage(errorCode)) {
            log.warn("Business failure with provider detail, code={}, traceId={}, raw={}",
                    errorCode.code(), traceId, exception.getMessage());
        }
        return ResponseEntity.status(statusFor(errorCode))
                .body(ApiResponse.failure(errorCode.code(),
                        publicFailureCopy.forBusinessEnvelope(errorCode, exception.getMessage()), traceId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldError() == null
                ? ErrorCode.VALIDATION.message()
                : exception.getBindingResult().getFieldError().getDefaultMessage();
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(ErrorCode.VALIDATION.code(), message, traceId(request)));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            // 覆盖「缺少必需请求头 / 请求参数」这类**调用方**错误。
            // 此前 MissingRequestHeaderException 未被处理，落到下面的兜底分支被报成 500 系统异常，
            // 调用方无法区分「自己传错了」和「服务端故障」，还会产生 ERROR 级全栈日志噪音。
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            ConstraintViolationException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure(ErrorCode.VALIDATION.code(), ErrorCode.VALIDATION.message(), traceId(request)));
    }

    /**
     * HTTP 协议级调用方错误：方法不允许（405）/ 不支持的请求媒体类型（415）/ 不可接受的响应媒体类型（406）。
     *
     * <p>与「缺请求头 / 缺参数」同族：若落兜底分支会被报成 500「系统异常」并输出 ERROR 级完整堆栈，
     * 调用方无法区分自己传错与服务端故障，还会污染监控里的 API 5xx 告警。HTTP 状态保留协议语义，
     * 业务码沿用文档化清单中的 40001（docs/05 §1.3）。
     */
    @ExceptionHandler({
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleHttpSemantics(Exception exception, HttpServletRequest request) {
        HttpStatus status;
        String message;
        if (exception instanceof HttpRequestMethodNotSupportedException) {
            status = HttpStatus.METHOD_NOT_ALLOWED;
            message = "请求方法不受支持";
        } else if (exception instanceof HttpMediaTypeNotSupportedException) {
            status = HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            message = "不支持的媒体类型";
        } else {
            status = HttpStatus.NOT_ACCEPTABLE;
            message = "不支持的响应媒体类型";
        }
        return ResponseEntity.status(status)
                // 显式指定 JSON：406 场景下请求的 Accept 本身就排除了 JSON，不指定会导致响应体无法写出
                // → 触发 ERROR 派发 → 被安全链当匿名请求拒绝成 401（真实 HTTP 实测），状态语义彻底丢失
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(ErrorCode.VALIDATION.code(), message, traceId(request)));
    }

    /**
     * 上传类调用方错误：超出 multipart 大小上限（413）与非法 multipart 请求（400）。
     *
     * <p>实测（本地真实 HTTP 探针）：6MB 上传（`spring.servlet.multipart.max-file-size=5MB`）
     * 抛 `MaxUploadSizeExceededException` 落兜底分支 → 500「系统异常」+ ERROR 全栈；
     * 生产前置 nginx 时用户看到的是 413，直连 API（功能回归环境）语义丢失且污染 5xx 告警。
     * 消息不含具体字节数：上限由配置持有，写死数字会漂移。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(
            MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(ErrorCode.VALIDATION.code(), "上传文件超出大小限制", traceId(request)));
    }

    /** 非法 multipart 表单（边界损坏、缺少 part 等）同样属调用方错误，不能报 500。 */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipart(
            MultipartException exception, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(ErrorCode.VALIDATION.code(), "上传表单不合法", traceId(request)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(
            NoResourceFoundException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure(ErrorCode.NOT_FOUND.code(), ErrorCode.NOT_FOUND.message(), traceId(request)));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(
            OptimisticLockingFailureException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure(ErrorCode.CONFLICT.code(), ErrorCode.CONFLICT.message(), traceId(request)));
    }

    /**
     * 完整性约束冲突（唯一键/外键等）：并发注册、并发创建同键资源等场景下，
     * 应用层先行检查通过的请求可能在提交时撞约束——这是稳定的「冲突」语义，不是 500。
     * 不记录异常 message（可能包含重复值等用户数据），只记 traceId 供排查。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        log.warn("Data integrity conflict during request handling, traceId={}", traceId(request));
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure(ErrorCode.CONFLICT.code(), ErrorCode.CONFLICT.message(), traceId(request)));
    }

    /**
     * 并发冲突（如 H2/MySQL 并发更新同一行）与唯一键冲突同属「调用方重试可能成功」的
     * 稳定冲突语义，统一 40901；乐观锁失败由其更具体的子类 handler 优先匹配。
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleConcurrencyFailure(
            ConcurrencyFailureException exception, HttpServletRequest request) {
        log.warn("Concurrent data conflict during request handling, traceId={}", traceId(request));
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failure(ErrorCode.CONFLICT.code(), ErrorCode.CONFLICT.message(), traceId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled request failure, traceId={}", traceId(request), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure(ErrorCode.INTERNAL.code(), ErrorCode.INTERNAL.message(), traceId(request)));
    }

    /**
     * 业务码 → HTTP 状态。**刻意不写 default**：switch 表达式对枚举做穷尽检查，
     * 新增 {@link ErrorCode} 时若忘记在此登记会**编译失败**，而不是静默降级为 500
     * （第五十批实测：新码漏登记时接口返回 500 而非 409）。
     */
    private HttpStatus statusFor(ErrorCode errorCode) {
        return switch (errorCode) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, CONSENT_REQUIRED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case CONFLICT, VERSION_ARCHIVED -> HttpStatus.CONFLICT;
            case INTERNAL, AI_FAILURE, PDF_FAILURE -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }
}
