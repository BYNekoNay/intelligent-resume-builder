package com.intelligentresume.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 导出账号数据前的再认证请求：导出包含全部个人数据，必须校验当前密码，
 * 不能仅凭 access token（丢失的设备/被借用的会话不应能拖走整份数据）。
 */
public record ExportDataRequest(@NotBlank @Size(min = 8, max = 128) String currentPassword) {}
