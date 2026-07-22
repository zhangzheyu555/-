# FLOW-01 今日待办与业务跳转隔离验收

**验收时间**：2026-07-21 17:04 CST
**结论**：**PASS（隔离复验）**
**范围**：仅桌面 Web 的 FLOW-01；仅本地 Maven/H2 合成数据与 Chromium API mock。未连接 QA MySQL、Docker、局域网或生产环境；未测试移动端；未修改 G3 台账、未提交代码。

## 修复的闭环契约

- `daily_loss_report.status = SUBMITTED` 现在生成只读的 `daily-loss-review-{reportId}` 待办，仅进入督导和老板的待办投影。记录携带白名单上下文：`reportId`、`storeId`、`month`、`lossDate`、`mode=review`。
- 投影读取仍严格按认证租户、品牌和门店范围过滤；督导日报损使用与 `DailyLossService` 一致的 `STORE` 数据范围。财务不获得日报损复核待办。
- 通用待办的“完成”和“上报老板”均明确拒绝日报损待办，返回 `DAILY_LOSS_REVIEW_WORKFLOW_REQUIRED`，并写拒绝审计。日报损只能通过既有 `DailyLossService.reviewReport` 状态机从 `SUBMITTED` 转为 `REVIEWED`；源状态变化后待办自然消失。
- 前端将服务端待办动作转换为本地白名单路由，绝不接受任意 URL 或任意查询参数。日报损、巡检和仓库分别保留记录/门店/月度上下文；浏览器路由不替代后端的租户、角色或范围校验。
- 督导工作台新增“每日报损复核”入口；路由允许已具备 `daily_loss.read` 的督导进入 `DailyLossPage`，该页只消费已授权门店与月份，并只打开已由服务器返回的 `reportId`。

## 定向 H2 / HTTP 边界证据

执行命令：

```bash
source /Users/a1/.zprofile
cd backend
mvn -q -Dtest=RoleTodoServiceTest,TodoAuthorizationBoundaryTest,RoleTodoControllerTest,DailyLossServiceTest test
```

- 退出码：`0`
- 定向测试：**53/53 通过**，0 failure、0 error、0 skipped。
  - `RoleTodoServiceTest`：28；含日报损 `SUBMITTED` 投影、租户隔离、督导/BOSS可见、财务不可见、白名单上下文、通用完成拒绝审计、`REVIEWED` 后投影消失。
  - `TodoAuthorizationBoundaryTest`：6；含督导仅按获授权门店调用日报损投影，未授权门店不查询。
  - `RoleTodoControllerTest`：6；认证用户和角色待办端点契约。
  - `DailyLossServiceTest`：13；既有提交/复核、范围、幂等和审计状态机回归。
- H2 均为进程内存库；进程结束后已释放。未写入 QA 基线或真实业务数据。

## 桌面 Chromium / 前端证据

执行命令：

```bash
cd frontend-vue
./node_modules/.bin/vue-tsc -b
E2E_BASE_URL=http://127.0.0.1:18175 \
  ./node_modules/.bin/playwright test tests/e2e/37-flow-role-todo-navigation.spec.ts --project=chromium
```

- `vue-tsc -b`：退出码 `0`。
- 新增桌面 Chromium：**2/2 通过**。
  - 1280×720 下，日报损待办跳转携带 `storeId/month/reportId/lossDate/mode`，自动打开该服务器返回的报损详情，无横向溢出、无控制台错误。
  - 巡检和仓库跳转保留 `recordId` / `requisitionId` 及门店、月份；注入的非白名单参数未进入 URL。
- 浏览器只使用 API mock；本地 Vite 使用临时端口 `18175` 并已停止。未启动后端服务或浏览器真实登录会话。

## 安全、审计与清理结论

- 报销、日报损、巡检、仓库的源状态机均未由通用待办改写；日报损拒绝完成/上报会单独记录 `operation_log`，成功复核继续由既有 `daily_loss_review` 审计记录。
- 待办投影不读取其他租户记录；受限门店查询由统一 `AccessControlService` 的范围检查与拒绝审计路径处理。
- H2、Chromium 会话、API mock 和临时 Vite 资源均已清理；未创建持久化数据库、附件、账号或会话。

## 残留风险

- 本结论是隔离 H2 与桌面 mock 证据，尚未在独立 QA MySQL 和受控真实登录会话中重演；该风险不在本轮隔离门禁内。
- 未执行移动端，符合当前“仅桌面 Web”范围。
- 本轮未重跑后端全量、打包或前端生产构建；它们应由最终汇总轮在所有并行修复完成后统一执行，不能以本报告替代。
