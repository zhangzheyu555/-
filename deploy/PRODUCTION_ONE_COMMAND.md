# 生产一命令部署

## 一次性服务器准备

1. 安装 Docker Engine 与 Compose Plugin，创建非 root 发布账号。
2. 将 `deploy/`、`.env.production`、`ssl/` 放入 `/opt/ai-profit-os/`，并将 `.env.production` 权限设为 `600`。
3. 复制 `deploy/.env.production.example` 为 `.env.production`，填写真实 MySQL 连接、GHCR 镜像仓库和服务域名；不得提交该文件。
4. 使用具有 `read:packages` 权限的 GitHub fine-grained token 在服务器执行一次 `docker login ghcr.io`。令牌只保存在 Docker 的凭据存储中。
5. 在 GitHub `production` Environment 配置审批人和下列 Secrets：`DEPLOY_HOST`、`DEPLOY_USER`、`DEPLOY_SSH_KEY`、`DEPLOY_KNOWN_HOSTS`。可选 Variables：`DEPLOY_DIRECTORY=/opt/ai-profit-os`。

## 手动部署

GitHub Actions 的 **Deploy production** 工作流会构建提交 SHA 镜像并自动执行服务器脚本。

如需在服务器手动部署已存在的镜像，只需一条命令：

```bash
cd /opt/ai-profit-os && APP_IMAGE_TAG=sha-<40位提交SHA> ./deploy/production-update.sh
```

脚本按顺序执行 `docker compose pull`、`docker compose up -d --remove-orphans --wait`。健康检查失败时命令失败，旧容器不会被脚本主动删除。

## 回滚

选择上一条已验证的提交 SHA，重复同一命令：

```bash
cd /opt/ai-profit-os && APP_IMAGE_TAG=sha-<上一条40位提交SHA> ./deploy/production-update.sh
```

应用镜像可以回滚；已由 Flyway 执行的数据库迁移不可自动回滚，必须通过新增修复迁移或经批准的数据库恢复处理。
