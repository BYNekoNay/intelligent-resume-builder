package com.intelligentresume.common.error;

public enum ErrorCode {
    VALIDATION(40001, "参数错误"),
    UNAUTHENTICATED(40101, "未登录或 Token 无效"),
    FORBIDDEN(40301, "无权限访问"),
    CONSENT_REQUIRED(40302, "AI 数据处理未授权或已撤回"),
    NOT_FOUND(40401, "资源不存在"),
    RATE_LIMITED(42901, "请求频率或 AI 配额超限"),
    CONFLICT(40901, "资源版本冲突或非法状态迁移"),
    INTERNAL(50001, "系统异常"),
    AI_FAILURE(50002, "AI 调用失败"),
    PDF_FAILURE(50003, "PDF 导出失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
