# 当前最新版本

日期：2026-07-06

当前最新版本以旧版门店利润系统页面为主线：

- 主入口：`http://127.0.0.1:8080/index.html`
- 前端：旧版 `index.html + database.js`
- 托管方式：Spring Boot 静态资源目录 `backend/src/main/resources/static/`
- 后端：Java Spring Boot
- 数据库：MySQL，数据库名 `store_profit`
- 数据读写：旧版前端通过 `/api/storage` 读写后端兼容存储，数据落到 MySQL
- 登录优化：登录页先显示，系统数据在后台加载，避免进入页面时长时间停在“正在加载数据”
- CloudBase：后端化版本已关闭 CloudBase 连接，避免本地环境卡顿

默认登录：

- 管理员：`123`
- 老板：`boss888`
- 店长：使用对应门店的 `code` 或 `id`

后续开发原则：

- 以旧版视觉和交互为基准继续改。
- 不再把 `web/` 下的新 Vue 工作台作为当前主版本。
- 新功能优先接入当前旧版页面和 Java/MySQL 后端。
- 除非明确要求，不推送远程仓库。
