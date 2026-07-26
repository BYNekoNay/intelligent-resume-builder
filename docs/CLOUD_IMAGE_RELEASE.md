# 云端镜像发布

服务器不构建业务镜像，也不直接连接 Docker Hub。`.gitee/workflows/publish-images.yml` 在云端构建并推送 API、Web、PDF、边缘 Nginx，以及镜像化的 MySQL 8.4 到 ACR。

## Gitee 配置

在 Gitee 的 `production` 环境中设置下列 Secret：

- `ACR_REGISTRY`：例如 `registry.cn-hangzhou.aliyuncs.com`
- `ACR_NAMESPACE`：ACR 命名空间
- `ACR_USERNAME`：仅有该命名空间推送权限的部署账号
- `ACR_PASSWORD`：部署账号密码或访问令牌

手动触发 `publish-registry-images` 时必须填写不可变的 `image_tag`，建议使用发布版本或提交 SHA。流水线不使用本地构建产物。

建议为流水线使用 ACR 写入权限账号，为 ECS 单独创建仅有拉取权限的账号。两个账号均不写入仓库、Compose 文件或 Gitee 普通变量。

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

若 ACR 仓库为私有仓库，先在 ECS 上使用只读部署账号登录一次：

```bash
printf '%s' "$ACR_PULL_PASSWORD" | docker login "$IMAGE_REGISTRY" \
  --username "$ACR_PULL_USERNAME" --password-stdin
```

该命令不会把密码写入 Compose 环境文件；Docker 会将受限认证信息保存在部署用户的 Docker 配置中。仓库设为公开时可跳过登录，但不建议将包含业务镜像的正式仓库公开。
