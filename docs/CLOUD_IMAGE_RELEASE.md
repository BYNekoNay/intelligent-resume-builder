# 云端镜像发布

服务器不构建业务镜像，也不直接连接 Docker Hub。`.gitee/workflows/publish-images.yml` 在云端构建并推送 API、Web、PDF、边缘 Nginx，以及镜像化的 MySQL 8.4 到 ACR。

## Gitee 配置

在 Gitee 的 `production` 环境中设置下列 Secret：

- `ACR_REGISTRY`：例如 `registry.cn-hangzhou.aliyuncs.com`
- `ACR_NAMESPACE`：ACR 命名空间
- `ACR_USERNAME`：仅有该命名空间推送权限的部署账号
- `ACR_PASSWORD`：部署账号密码或访问令牌

手动触发 `publish-registry-images` 时必须填写不可变的 `image_tag`，建议使用发布版本或提交 SHA。流水线不使用本地构建产物。

## 服务器运行

服务器环境文件应设置与发布任务一致的 `IMAGE_REGISTRY`、`IMAGE_NAMESPACE`、`IMAGE_TAG`。使用 registry 覆盖层启动：

```bash
cd /opt/intelligent-resume/app/deploy
docker compose --env-file production.ip-test.env \
  -f docker-compose.prod.yml \
  -f docker-compose.registry.yml \
  -f docker-compose.ip-test.yml pull
docker compose --env-file production.ip-test.env \
  -f docker-compose.prod.yml \
  -f docker-compose.registry.yml \
  -f docker-compose.ip-test.yml up -d
```

`docker-compose.registry.yml` 移除了业务服务的 `build` 配置，确保 ECS 只拉取云端发布的成品镜像。
