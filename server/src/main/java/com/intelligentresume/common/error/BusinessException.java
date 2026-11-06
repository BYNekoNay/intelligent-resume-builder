package com.intelligentresume.common.error;

/**
 * 业务异常：携带 {@link ErrorCode}，由 {@code GlobalExceptionHandler} 统一翻译成
 * 「HTTP 状态 + 响应信封」。
 *
 * <p>【小白导览】业务代码里表达「这事办不成」的标准姿势：不自己拼 HTTP 响应，而是
 * {@code throw new BusinessException(ErrorCode.XXX, "给用户看的中文话")}，剩下的翻译工作
 * 全局异常处理器统一做。改坏它（乱用它）→ 用户看到英文报错或 500，前端文案映射失效。
 *
 * <p>使用约束：
 * <ul>
 *   <li>message 是**给用户看的中文文案**，不要把上游/框架原文塞进来（AI、PDF 两类会经
 *       PublicFailureCopy 改写，其余原样透出）；</li>
 *   <li>不要把 4xx 场景包装成 RuntimeException 或反过来——{@code ErrorCode} 同时决定 HTTP 状态；</li>
 *   <li>异常不参与事务控制：抛出即让当前 @Transactional 回滚，需要「即使回滚也保留」的写入
 *       必须走独立事务（参考 refresh 的撤销逻辑）。</li>
 * </ul>
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /** 用枚举自带文案构造（多数场景用这个重载）。 */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    /** 用更具体的用户文案覆盖枚举默认文案；错误码（及对应 HTTP 状态）不变。 */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
