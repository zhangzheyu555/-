# EMP-01～EMP-04 员工与培训隔离重验报告

执行时间：2026-07-21（Asia/Shanghai）
执行范围：EMP-01 成功写入审计修复与 EMP-01～EMP-04 桌面回归；仅进程内 H2、浏览器 API mock 和本机回环 Vite。

## 结论

**BLOCKED**。员工成功写入审计缺口已解除，但 EMP-03 的桌面 Chromium 考试答题流程被现有布局问题阻断；不将未完成的 EMP-01～EMP-04 写为通过。

## 已解除的原阻断

- `EmployeeService` 对新增、修改、离职和创建/关联员工登录账号写入统一 `operation_log`，使用现有 `AuditRepository` 和事务边界。
- 审计目标为 `employee`，包含操作人、员工标识、门店和成功原因；快照只含状态、雇佣类型、职位和账号关联/启用状态。
- 审计快照不包含姓名、手机号、工资、身份证、健康证、生日或初始密码；失败和账号关联失败均不产生半写入或成功审计。
- 定向 H2：`EmployeeServiceAuditH2Test` 3/3、`EmployeeServicePermissionTest` 2/2 通过（合计 5/5）。其中覆盖成功审计、敏感字段脱敏、失败回滚、跨店/跨租户 403 且拒绝审计、未知 ID 404、权限读取边界。

## 当前桌面阻断

- `frontend-vue/tests/e2e/35-employee-training-desktop.spec.ts` 的 EMP-03 用例“employee sees only own profile and completes assigned training exam at 1280px”失败：考试弹窗在 1280px 下向左延伸到固定桌面侧栏下方，侧栏 `nav.sidebar-navigation` 截获“立即上报”单选按钮的指针事件，`locator.check()` 超时。
- 证据：Playwright 错误明确为侧栏拦截指针事件；截图显示考试弹窗左缘被侧栏遮挡。此问题阻止完整员工培训考试流程，不能将 EMP-01～EMP-04 标为 PASS。
- 同一桌面脚本的其余两个隔离用例已通过：店长员工档案新增/开号/离职/重复失败反馈，以及员工/店长即使持有陈旧考试管理权限也不能进入督导考试管理（2/2）。

## 隔离与清理

- H2 使用随机 `jdbc:h2:mem:` 数据源，测试进程退出后已释放；未连接 QA MySQL、Docker、局域网或生产。
- Chromium API 全部由路由 mock 返回合成数据；临时 Vite 仅绑定 `127.0.0.1:18174`，已停止，无持久化会话或业务数据。
- 未修改 `g3-gate-ledger.md`，未提交代码；未执行共享的全量 Maven、打包或前端类型/生产构建。

## 最小解锁与后续验证

1. 修复考试弹窗在桌面侧栏布局下的定位/层叠上下文，确保 1280px 的表单控件不被导航遮挡；不改变考试权限、业务接口或数据范围。
2. 重跑 `35-employee-training-desktop.spec.ts --project=chromium`，并确认 3/3、无控制台错误及无横向溢出。
3. 由主线程在共享工作区无并发时执行：
   - `cd backend && mvn -q test`
   - `cd backend && mvn -q -DskipTests package`
   - `cd frontend-vue && ./node_modules/.bin/vue-tsc -b`
   - `cd frontend-vue && ./node_modules/.bin/vite build`

全部完成后，才可重新判定 EMP-01～EMP-04。
