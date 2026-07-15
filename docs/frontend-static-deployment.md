# 历史静态前端部署说明（已归档）

> 此文档不再是发布手册。当前正式前端只有 `frontend-vue`；不得再部署、修改或把流量切到旧 HTML、`runtime-static`、`web` 或 `backend/src/main/resources/static`。

## 当前正式发布入口

- 前端构建与发布：[Vue3 生产部署说明](./vue3-production-deployment.md)
- Nginx 配置模板：[Vue3 Nginx 配置](./nginx-vue3-ai-profit-os.conf)
- 后端发布、备份和回滚：[生产备份与恢复](./production-backup-restore.md)

正式访问规则：

- `/` 和业务页面由 Vue3 `frontend-vue/dist` 提供。
- `/api/**` 由 Spring Boot 提供。
- 生产发布前必须执行 `frontend-vue` 的 `npm run build`，并按 Vue3 部署说明完成备份、上传和健康检查。

## 本文档保留目的

本文件仅记录历史上曾存在静态页面的部署边界，方便排查遗留服务器目录。它不包含可执行部署命令，也不能作为上线、回滚或故障恢复依据。

如服务器仍存在旧静态文件，必须将其与正式 Vue3 构建产物隔离；未经过专项迁移验收，不得让旧页面写入任何业务数据。
