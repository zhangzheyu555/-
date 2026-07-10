# AI Profit OS 生产备份与恢复说明

## 目标

上线前必须确认前端、后端、数据库和关键配置都有可执行的备份与回滚路径。本文只写流程和命令模板，不记录数据库密码、API Key、DeepSeek Key 或其他敏感信息。

## 服务器目录

```text
/opt/store-profit/
├─ backend/
│  └─ store-profit-backend-0.1.0-SNAPSHOT.jar
├─ frontend/
│  └─ Vue3 dist 构建产物
├─ legacy-frontend/
│  └─ 旧 HTML 兜底文件
├─ frontend-backup/
├─ backend-backup/
├─ mysql-backup/
└─ logs/
```

## MySQL 备份

建议每天至少备份一次，重大上线前必须手动备份一次。

备份命令模板：

```bash
mkdir -p /opt/store-profit/mysql-backup
mysqldump \
  -h 127.0.0.1 \
  -u store_profit_user \
  -p \
  --single-transaction \
  --routines \
  --triggers \
  store_profit \
  > /opt/store-profit/mysql-backup/store_profit-$(date +%Y%m%d-%H%M%S).sql
```

恢复命令模板：

```bash
mysql -h 127.0.0.1 -u store_profit_user -p store_profit < /opt/store-profit/mysql-backup/store_profit-YYYYMMDD-HHMMSS.sql
```

恢复前要求：

- 停止 Java 后端或切维护窗口，避免恢复过程中继续写入。
- 确认目标库名称正确。
- 先保留当前库的二次备份。
- 恢复后执行 `/api/health` 和关键角色登录验证。

## Vue3 前端备份与回滚

正式前端目录：

```text
/opt/store-profit/frontend/
```

发布脚本：

```powershell
.\scripts\deploy-vue3-frontend.ps1 -Server 服务器IP -PublicUrl "http://服务器IP:18080/"
```

脚本会在上传前备份旧前端到：

```text
/opt/store-profit/frontend-backup/yyyyMMdd-HHmmss/
```

列出备份：

```powershell
.\scripts\rollback-vue3-frontend.ps1 -Server 服务器IP -List
```

恢复最近一次备份：

```powershell
.\scripts\rollback-vue3-frontend.ps1 -Server 服务器IP -PublicUrl "http://服务器IP:18080/"
```

前端回滚不需要重启 Java。

## 后端 jar 备份与回滚

后端目录：

```text
/opt/store-profit/backend/
```

上线前备份当前 jar：

```bash
mkdir -p /opt/store-profit/backend-backup/$(date +%Y%m%d-%H%M%S)
cp /opt/store-profit/backend/store-profit-backend-0.1.0-SNAPSHOT.jar \
   /opt/store-profit/backend-backup/$(date +%Y%m%d-%H%M%S)/
```

上传新 jar 后重启：

```bash
systemctl restart store-profit
curl -f http://127.0.0.1:8080/api/health
```

回滚 jar：

```bash
cp /opt/store-profit/backend-backup/备份目录/store-profit-backend-0.1.0-SNAPSHOT.jar \
   /opt/store-profit/backend/store-profit-backend-0.1.0-SNAPSHOT.jar
systemctl restart store-profit
curl -f http://127.0.0.1:8080/api/health
```

## 旧 HTML 兜底

旧 HTML 不删除，放在：

```text
/opt/store-profit/legacy-frontend/
```

兜底地址：

```text
http://服务器IP:18080/legacy/index.html
```

旧版只作为临时兜底，不再作为默认入口。

## 配置备份

上线前备份：

- Nginx 配置。
- systemd service。
- `application.yml` 中非敏感配置。

不要把数据库密码、API Key 或 token 写入仓库文档。生产敏感值应通过环境变量、服务器配置或密钥管理服务提供。

## 恢复后验证

每次恢复后至少验证：

- `/api/health` 正常。
- `/`、`/login`、`/boss`、`/finance`、`/warehouse`、`/inspection`、`/operations`、`/todos` 可访问。
- `/legacy/index.html` 可访问。
- 6 个角色登录和默认首页正确。
- 关键写入动作仍落 MySQL。
- 操作日志继续写入。
