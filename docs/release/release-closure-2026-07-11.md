# 发布整改收口记录（2026-07-11）

## 结论

当前工作区已形成可复现构建的发布候选范围，但整体发布结论仍为 **BLOCKED**。
阻塞原因不是编译失败，而是业务备份仍存在于 Git 历史；历史改写必须由仓库负责人批准并协调强制更新，不能在普通开发工作区擅自执行。

本轮没有提交、推送、清空数据库、改写已执行迁移或生成模拟业务数据。

## 已完成

- 将考试学习源码、V30-V35 迁移及配套测试纳入发布候选；新增 V36 平台回调幂等事件迁移。
- 将 119 张培训图片从文档目录等内容移动到后端运行时静态资源目录；逐文件哈希与原 Git 对象一致。
- 删除工作区中的 `store-data-backup.json`，并由 CI 阻止业务备份再次进入 Git。
- 保留 `frontend-vue/index.html`，移除 `legacy.css`、`components/legacy` 与运行时 `legacy-*` 依赖。
- `/api/todos` 和 `/api/exam-center` 被确认为权威接口；旧聚合/考试查询只读兼容入口带弃用响应头，旧考试写入口返回 410。
- 数据录入页对品牌、门店、月份、历史记录、刷新及离开路由统一启用可访问的未保存确认弹窗。
- Vue 路由改为动态导入，入口包降到约 165 kB。
- 饿了么 Webhook 默认关闭并 fail-closed；显式启用时使用独立密钥校验原始请求体，只保存事件元数据和载荷 SHA-256，并拒绝同事件编号的异载荷。
- 建立 `PlatformAdapter` 状态边界；美团只返回 `NOT_CONFIGURED`，没有模拟接口或模拟营业数据。
- 数据库连接改用显式 `sslMode`；PRODUCTION 必须使用 `VERIFY_IDENTITY`，且禁止 public key retrieval。
- Spring Boot 升级至 3.5.16；Vite 升级至 8.1.4。
- CI 增加源码完整性、业务备份排除、后端测试/打包、MySQL 8 空库 Flyway、Vue 类型/构建、全依赖审计、差异检查及受控 E2E。

## 本机验证

| 检查 | 结果 |
| --- | --- |
| 后端测试 | PASS，干净索引副本 53 个套件、240 项测试、0 失败/错误/跳过 |
| 后端打包 | PASS，Spring Boot 3.5.16 |
| H2 测试迁移 | PASS，迁移到 V36 |
| Vue 生产构建 | PASS，Vite 8.1.4，入口约 165 kB |
| npm 全依赖审计 | PASS，0 个漏洞 |
| 培训运行资源 | PASS，Jar 中 119 张图片 |
| V36 打包 | PASS，Jar 包含 MySQL V36 迁移 |
| `git diff --check` | PASS |
| 数据录入浏览器防误操作 | PASS，取消后内容保留，确认后才离开，无业务写入 |

真实 MySQL 8 空库迁移由新增 CI 作业执行。本轮没有对正在运行且当前为 V35 的本地 3308 业务库应用 V36，避免越权修改现有数据。

## 兼容边界

- `/api/boss/todo-dashboard` 仍是工作台聚合读模型，不再作为新业务写入口；`/api/boss/todos` 的上报/关闭流程尚未完全映射到 `business_todo` 状态机。
- 旧 `/api/operations/exam-*` GET 暂保留只读兼容；确认无旧客户端后再删除。考试写操作只允许新考试中心流程。
- 饿了么真实签名头、事件编号头和算法必须在平台或接入网关契约确认后才可启用；订单补拉、重试和对账仍待真实联调。
- HttpOnly Cookie 与 CSRF 防护涉及登录协议迁移，尚未完成；当前 Bearer Token/localStorage 方案仍是发布安全风险。
- 培训考试大页面拆分和受控资料附件上传尚未完成。

## 历史净化阻塞

敏感对象曾出现在两个路径：

- `store-data-backup.json`
- `backend/src/main/resources/static/门店数据备份.json`

批准后的处理步骤见 `docs/security/store-data-backup-history-remediation.md`。在凭据轮换、历史改写审批、全部 refs 净化和协作者重新克隆完成前，不得发布或推送。
