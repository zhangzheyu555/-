# 当前版本

日期：2026-07-10

当前正式版本为 Vue3 多门店经营管理系统：

- 正式前端：`frontend-vue`（Vue 3、TypeScript、Pinia、Vue Router）。
- 正式后端：`backend`（Spring Boot、JdbcTemplate、Flyway）。
- 真实数据源：MySQL。
- 正式入口：Vue3 应用；开发环境为 `http://127.0.0.1:5173/`，生产环境由 Nginx 托管 `frontend-vue/dist`。
- 后端接口：`http://127.0.0.1:8080/api/**`。

旧版边界：

- 顶层 `index.html`、`database.js`、`runtime-static/` 和 `backend/src/main/resources/static/` 仅用于旧版视觉、交互和数据迁移参考。
- 旧 HTML 只能经 `/legacy/` 只读入口回看，不能作为正式入口或新功能承载位置。
- `web/` 是冻结实验目录，不再继续开发。

账号与数据安全：

- 生产环境不创建默认账号、固定密码或示例业务数据。
- 账号由系统管理员在“账号权限”中创建和维护。
- 业务数据、附件、待办和操作日志均写入 MySQL；浏览器 `localStorage` 仅保存登录会话。

当前开发原则：

- 旧 HTML 仅作为样式与交互参考，不再作为业务标准或运行时数据来源。
- 新功能优先复用 Vue3、Spring Boot、MySQL 和 Flyway。
- 金额、成本、利润与比例由后端使用 `BigDecimal` 计算。
- 不推送远程仓库，除非用户明确要求。
