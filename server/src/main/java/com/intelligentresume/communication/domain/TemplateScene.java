package com.intelligentresume.communication.domain;

/**
 * 沟通模板场景。与 {@link CommunicationType}（载体类型）解耦，
 * 模板实体同时持有 scene + templateType 两个维度。
 */
public enum TemplateScene {
    FOLLOW_UP,
    THANK_YOU,
    SALARY,
    DECLINE,
    GENERAL
}
