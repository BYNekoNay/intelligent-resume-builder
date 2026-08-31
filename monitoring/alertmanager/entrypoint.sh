#!/bin/sh
# 渲染 Alertmanager 配置后启动服务。
#
# 仓库内的 alertmanager.yml 只含占位符；敏感值（机器人 Webhook、SMTP 凭据）
# 在容器启动时由环境变量注入，保证敏感信息不进入 Git 历史。
#
# 说明：Alertmanager 自身不展开配置中的环境变量，因此这里用 sed 渲染占位符
# （__VAR__）后，再以渲染结果启动。占位符刻意不使用 ${VAR} 语法，避免被
# shell 提前展开导致替换失效。
set -eu

TEMPLATE=/etc/alertmanager/alertmanager.template.yml
RENDERED=/etc/alertmanager/alertmanager.yml

# 钉钉/企业微信机器人 Webhook URL。未配置时指向本机丢弃端口（9），
# 保证配置可加载、容器不崩溃；告警投递失败会记录到 Alertmanager 日志。
: "${ALERTMANAGER_WEBHOOK_URL:=http://127.0.0.1:9/unconfigured-alertmanager-webhook}"

# SMTP 邮件告警。SMTP_USER/SMTP_PASS 可留空（部分中继无需认证）。
: "${SMTP_HOST:=localhost}"
: "${SMTP_PORT:=25}"
: "${SMTP_USER:=}"
: "${SMTP_PASS:=}"
: "${SMTP_FROM:=alertmanager@localhost}"
: "${SMTP_TO:=root@localhost}"

sed \
  -e "s|__ALERTMANAGER_WEBHOOK_URL__|${ALERTMANAGER_WEBHOOK_URL}|g" \
  -e "s|__SMTP_SMARTHOST__|${SMTP_HOST}:${SMTP_PORT}|g" \
  -e "s|__SMTP_USER__|${SMTP_USER}|g" \
  -e "s|__SMTP_PASS__|${SMTP_PASS}|g" \
  -e "s|__SMTP_FROM__|${SMTP_FROM}|g" \
  -e "s|__SMTP_TO__|${SMTP_TO}|g" \
  "$TEMPLATE" > "$RENDERED"

exec alertmanager --config.file="$RENDERED" --storage.path=/alertmanager
