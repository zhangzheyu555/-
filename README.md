# 门店利润系统

这是门店利润系统。当前最新版本以旧版页面为主线，并由 Java Spring Boot 后端托管，数据写入 MySQL。

当前版本说明见 [CURRENT_VERSION.md](./CURRENT_VERSION.md)。

## 文件说明

- `index.html`: 系统入口页面
- `database.js`: 基础数据、账号和数据访问层
- `cloudbase.full.js`: 腾讯云 CloudBase SDK
- `store-data-backup.json`: 门店数据备份文件，可在系统内导入恢复
- `backend/`: Java Spring Boot 后端，提供 MySQL 持久化、兼容存储接口，并托管当前旧版前端
- `web/`: 新版前端工作台实验目录，当前不作为主版本
- `docs/`: 后端迁移设计和实施计划

## 本地使用

推荐启动后端后访问：

```text
http://127.0.0.1:8080/index.html
```

旧版页面也仍可直接打开，但直接打开时会回退到浏览器本地存储，不是当前推荐方式。

默认登录密码：

- 管理员：`123`
- 老板：`boss888`
- 店长：使用对应门店的 `code` 或 `id`

## 部署

当前推荐部署方式是启动 `backend/`，由 Spring Boot 同时提供旧版页面和 `/api/storage` 后端接口。

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

访问：

```text
http://127.0.0.1:8080/index.html
```

此时旧版前端会同源调用 `/api/storage`，数据写入 MySQL。

## 新版前端工作台

新版前端放在 `web/`，当前只作为实验目录，不作为最新主版本。

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
