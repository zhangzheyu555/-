# 员工服务助手部署说明（已并入双助手安全流程）

员工服务助手不能再单独部署。为了避免新 Java 进程丢失门店经营助手的 `DEEPSEEK_*` 配置，所有候选和正式替换都必须使用统一的双助手 DPAPI 配置与启动入口。

请遵循 [双助手安全部署](assistant-unified-secure-deployment.md)。它同时覆盖：

- 门店经营助手 `DEEPSEEK_*` 的独立配置、上游检查和降级行为；
- 员工服务助手 `EMPLOYEE_ASSISTANT_*` 的 REMOTE/MODEL 互斥配置与数据隔离；
- 当前 Windows 用户 DPAPI 加密运行配置、候选 `18082` 门禁和受控替换 `18081`；
- 旧单助手入口的弃用边界。

不得使用 `start-backend-employee-assistant-model-secure.ps1`、`start-backend-deepseek-secure.ps1` 或 `configure-employee-assistant-once.ps1` 启动服务或创建账号。这些旧入口现在只会安全拒绝执行，不能绕过统一流程。
