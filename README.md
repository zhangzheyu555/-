# AI Profit OS 多门店经营管理系统

这是面向老板、财务、店长、督导、仓库、运营和系统管理员的多门店经营管理系统。系统围绕经营数据录入、利润计算、异常识别、待办处理、复核和操作留痕建立闭环。

`frontend-vue` 是唯一正式前端，`backend` 是唯一正式后端，MySQL 是唯一真实业务数据源。旧 HTML 仅保留为视觉和交互参考，后续通过只读 `/legacy` 入口访问。

产品蓝图、角色权限和旧版迁移边界见：

- [产品蓝图](./docs/product-blueprint.md)
- [角色权限矩阵](./docs/role-permission-matrix.md)
- [旧版迁移清单](./docs/legacy-migration-inventory.md)

## 目录说明

- `frontend-vue/`: 正式 Vue3 前端。
- `backend/`: 正式 Spring Boot 后端、Flyway 迁移与 MySQL 访问层。
- `docs/`: 产品、权限、部署和迁移说明。
- `index.html`、`database.js`、`runtime-static/`、`backend/src/main/resources/static/`: 历史兼容代码，仅作只读回看和视觉参考。
- `web/`: 已冻结的实验目录，不再新增功能。

## 本地开发

后端依赖 MySQL，账号、密码和第三方密钥均通过环境变量注入。生产环境不会自动创建默认账号、固定密码或示例业务数据。

## 部署

当前推荐部署方式是由 Nginx 托管 `frontend-vue/dist`，`backend/` 仅提供 `/api/**`。旧 HTML 只部署到外置的 `/legacy/` 回退路径，不能作为默认入口或由 Spring Boot 根路径直接提供。

### 本地 Docker 一键部署

源码根目录提供 `docker-compose.dev.yml`，用于本地开发和联调；它会从源码构建 Vue3 前端、Spring Boot 后端和巡检识别服务，并同时启动 MySQL、Redis、PostgreSQL 及本地 Nginx 网关。微信小程序不包含在 Docker 部署中。

前提是已安装 Docker Desktop（Windows/macOS）或 Docker Engine + Compose Plugin（Linux）。在项目根目录执行：

```bash
docker compose -f docker-compose.dev.yml up -d --build
```

首次构建会下载 Maven、Node、MySQL、Redis、PostgreSQL 和 Python/YOLO 依赖，耗时会明显更长。全部健康检查通过后访问：

```text
管理后台：http://127.0.0.1:8088/admin/
后端健康检查：http://127.0.0.1:8080/api/health
```

停止服务但保留本地数据：

```bash
docker compose -f docker-compose.dev.yml down
```

开发数据库、缓存和附件数据使用独立的 `development-*` Docker volumes；如需清空开发数据并重新初始化，执行 `docker compose -f docker-compose.dev.yml down -v`。默认密码仅用于本机隔离开发容器，可通过 `DEV_MYSQL_*`、`DEV_REDIS_PASSWORD` 和 `DEV_POSTGRES_PASSWORD` 环境变量覆盖，禁止用于服务器端部署。

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
$env:APP_ENV="<TEST、QA、STAGING 或 PRODUCTION>"
$env:MYSQL_HOST="<数据库地址>"
$env:MYSQL_PORT="<数据库端口>"
$env:MYSQL_DATABASE="<数据库名称>"
$env:MYSQL_USERNAME="<数据库账号>"
$env:MYSQL_PASSWORD="<数据库密码>"
mvn spring-boot:run
```

运行环境和五项数据库环境变量均为必填项。后端不会使用默认账号、默认密码或自动创建数据库；缺少配置时启动必须失败。`TEST`、`QA` 环境的数据库名还必须包含 `test` 或 `qa`，避免误连真实业务库。

健康检查：

```text
http://localhost:8080/api/health
```

后端默认监听：

```text
http://127.0.0.1:8080/api/health
```

## Vue3 前端

```powershell
cd frontend-vue
npm install
npm run dev
```

开发入口：

```text
http://127.0.0.1:5173
```

Vue3 开发服务器通过代理访问 `http://localhost:8080/api/**`。生产部署与旧版只读入口说明见 [docs/vue3-production-deployment.md](./docs/vue3-production-deployment.md)。
