package com.intelligentresume.communication.domain;

/**
 * 沟通模板场景。与 {@link CommunicationType}（载体类型）解耦，
 * 模板实体同时持有 scene + templateType 两个维度。
 *
 * <p>场景含义（一句话）：FOLLOW_UP=投递后跟进催回复；THANK_YOU=面试后致谢；
 * SALARY=谈薪/薪资沟通；DECLINE=婉拒 offer 或邀约；GENERAL=通用兜底。
 */
public enum TemplateScene {
    FOLLOW_UP,
    THANK_YOU,
    SALARY,
    DECLINE,
    GENERAL
}
