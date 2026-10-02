package com.intelligentresume.common.error;

public enum ErrorCode {
    VALIDATION(40001, "参数错误"),
    UNAUTHENTICATED(40101, "未登录或 Token 无效"),
    FORBIDDEN(40301, "无权限访问"),
    CONSENT_REQUIRED(40302, "AI 数据处理未授权或已撤回"),
    NOT_FOUND(40401, "资源不存在"),
    RATE_LIMITED(42901, "请求频率或 AI 配额超限"),
    CONFLICT(40901, "资源版本冲突或非法状态迁移"),
    /**
     * 归档版本被下游能力消费（ATS/导出/投递/沟通/评分/面试）。
     *
     * <p>与 {@link #CONFLICT} 区分：归档是**可逆**状态，用户可据此「先恢复再重试」；
     * 而状态机冲突/乐观锁的处置是「刷新」或「换一个版本」。前端按业务码映射文案
     * （不透传服务端 message），若无专属码，用户只会看到泛化的「内容已更新…请刷新后重试」，
     * 与真实处置动作不符。
     */
    VERSION_ARCHIVED(40902, "简历版本已归档"),
    ACCOUNT_DELETION_PENDING(40303, "账号处于删除撤销期"),
    ACCOUNT_DELETION_WINDOW_EXPIRED(40903, "删除撤销期已结束"),
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
