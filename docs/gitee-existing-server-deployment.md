# Gitee Go：现有腾讯云服务器自动部署

适用既有服务器目录 `/root/store-profit`。此流程不新建服务器、不迁移数据卷、不使用镜像仓库。

## 服务器一次性准备

将 `deploy/local-release-update.sh` 放到 `/root/store-profit/deploy/`，并将
`deploy/restricted-release-wrapper.sh` 安装为 root 管理的固定入口：

```bash
chown root:root /root/store-profit/deploy/local-release-update.sh
chmod 0755 /root/store-profit/deploy/local-release-update.sh
# 先从受控管理端上传 wrapper 到 /tmp/store-profit-release
install -o root -g root -m 0755 /tmp/store-profit-release /usr/local/sbin/store-profit-release
mkdir -p /root/store-profit/incoming /root/store-profit/backup
chown root:root /root/store-profit/incoming /root/store-profit/backup
chmod 0700 /root/store-profit/incoming /root/store-profit/backup
```

保留现有 `docker-compose.yml`、`.env`、`ssl/` 与全部 Docker 命名卷。发布前确保生产数据库已有可恢复备份。

Gitee Go 不应使用 root SSH 密钥。创建只用于发布的 `deploy` 用户，允许其写入
`/home/deploy/releases/`，并仅允许免密执行以下固定命令：

```text
deploy ALL=(root) NOPASSWD: /usr/local/sbin/store-profit-release *
```

该入口只接受合法发布版本号，将两个压缩包复制到 root 管理的 `incoming/` 目录后，才调用
`local-release-update.sh`。`deploy` 不加入 `docker` 组，也不拥有普通 root sudo 权限。

## Gitee Go 流水线步骤

1. 后端测试与打包：

```bash
(cd backend && mvn -B -ntp test && mvn -B -ntp -DskipTests package)
```

2. 前端构建：

```bash
(cd frontend-vue && npm ci && npm run build)
```

3. 打包产物。`RELEASE_ID` 使用当前提交 SHA：

```bash
tar -czf backend-release.tar.gz -C backend/target store-profit-backend-0.1.0-SNAPSHOT.jar
tar -czf frontend-release.tar.gz -C frontend-vue dist
```

4. 使用 Gitee Go 的 `deploy` SSH 私钥将两个压缩包上传到：

```text
/home/deploy/releases/<RELEASE_ID>/
```

5. 远程执行：

```bash
sudo -n /usr/local/sbin/store-profit-release <RELEASE_ID>
```

脚本会备份旧的 Jar 和 `dist` 到 `backup/artifact-release-<RELEASE_ID>-<时间>/`，然后本地执行 `docker compose build backend frontend`、`docker compose up -d backend frontend gateway`，最后验证后端和网关健康检查。

若后端包含 Flyway 迁移，启动会执行前向迁移。脚本不会自动回滚数据库迁移；失败时保留备份路径供人工评估后回退应用产物。
