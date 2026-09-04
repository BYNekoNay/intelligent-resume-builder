package com.intelligentresume.jobdescription.dto;

/**
 * 仅用于展示关联关系的岗位轻量引用。
 *
 * <p>不包含岗位正文和解析 JSON，避免在只需要标题/公司时读取大字段。
 */
public record JobDescriptionReference(Long id, String title, String companyName) {
}
