# 门店利润系统

这是门店利润系统。当前前端页面仍可直接打开使用；项目正在逐步迁移为 Java + MySQL 前后端分离架构。

## 文件说明

- `index.html`: 系统入口页面
- `database.js`: 基础数据、账号和数据访问层
- `cloudbase.full.js`: 腾讯云 CloudBase SDK
- `store-data-backup.json`: 门店数据备份文件，可在系统内导入恢复
- `backend/`: Java Spring Boot 后端，提供 MySQL 持久化和兼容存储接口
- `web/`: 新版前端工作台，全面重构阶段 1 骨架
- `docs/`: 后端迁移设计和实施计划

## 本地使用

双击 `index.html`，或使用任意静态服务器打开本目录。

默认登录密码：

- 管理员：`123`
- 老板：`boss888`
- 店长：使用对应门店的 `code` 或 `id`

## 部署

前端仍可静态部署。后端迁移期间，推荐本地启动 `backend/`，让前端优先通过 `/api/storage` 读写 MySQL。

## 后端开发

先创建 MySQL 数据库：

```sql
create database if not exists store_profit
  default character set utf8mb4
  default collate utf8mb4_unicode_ci;
```

启动后端：

```powershell
cd backend
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="你的MySQL密码"
mvn spring-boot:run
```

健康检查：

```text
http://localhost:8080/api/health
```

前端可以用任意静态服务器打开，例如在项目根目录运行：

```powershell
python -m http.server 5500
```

然后访问：

```text
http://localhost:5500/index.html
```

此时前端会优先调用 `http://localhost:8080/api/storage`。如果直接双击 `index.html`，仍会回退到本地存储。

## 新版前端工作台

全面重构期间，新前端放在 `web/`，不影响旧 `index.html`。

```powershell
cd web
npm install
npm run dev
```

访问：

```text
http://127.0.0.1:5173
```

新前端开发服务器会把 `/api` 代理到 `http://localhost:8080`。
