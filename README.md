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
