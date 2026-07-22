# EMP-01～EMP-04 员工与培训隔离验收报告

执行时间：2026-07-21（Asia/Shanghai）
执行范围：G3 / EMP-01～EMP-04；仅桌面 Web；隔离 H2 合成数据与 Chromium 浏览器 mock。

## 结论

**BLOCKED**。本次唯一原始阻断项“员工成功写入缺少 `operation_log` 审计”已修复并通过定向 H2 回归；但 EMP-03 桌面考试作答被侧栏遮挡，且全量后端、前端类型/生产构建各有工作区既有基线阻断，故 EMP-01～EMP-04 不能标记为 PASS，也不进入后续模块。

## 已通过的修复与取证

- `EmployeeService` 的新增、编辑、办理离职、创建/关联登录账号均纳入事务，并通过既有 `AuditRepository.writeLog` 写入 `operation_log`。
- 成功审计包含操作者、动作、员工目标、门店、成功结果及必要的前后状态；状态快照只含在职状态、用工类型、岗位及账号关联/启用状态，不含密码、身份证、姓名、手机号或工资。
- 对“存在但属其他租户”的员工 ID，服务层按既有拒绝审计规则记录后返回 403；真正未知 ID 保持 404，未改变角色上限、数据范围、接口返回或状态流转。
- `EmployeeServiceAuditH2Test`、`EmployeeControllerAuthorizationTest`、`EmployeeServicePermissionTest`、`ExamLearningFlowTest` 与列注释契约共 **8/8** 通过：覆盖四类成功审计、失败/关联账号异常回滚零写入、跨店/跨租户 403、未知 ID 404、匿名 401、拒绝审计字段及考试既有流程。
- 1280px Chromium mock：员工档案新增、开号、离职、重复失败反馈与无横向溢出通过；陈旧 `exam.manage` 权限也不能令员工或店长进入督导考试管理路由，相关控制台无非预期错误。
- 后端 `mvn -q package -DskipTests` 通过。

## 阻断证据

1. **EMP-03 桌面 Chromium（直接影响业务流程）**：`ExamCenterPage` 的考试作答弹层位于桌面侧栏下层，侧栏拦截单选题点击；1280px 用例 `employee sees only own profile and completes assigned training exam` 在选择“立即上报”时超时。为避免绕过真实交互，未使用强制点击，未修改超出本次“员工审计后端及直接测试”范围的前端层级。
2. **后端全量**：`mvn -q test` 为 **844 项，1 失败，0 错误**。`AdminBootstrapCommandTest.bootstrapFlywayCandidateMatchesContiguousMigrationResources` 发现工作区已有未跟随更新的 `V77__qmai_encrypted_credentials.sql`，而 `AdminBootstrapCommand.EXPECTED_FLYWAY_VERSION` 仍为 76；与本次员工审计无关，未越界修改。
3. **前端类型/生产构建**：`npx vue-tsc -b` 及 `npm run build` 均被 `src/pages/PlatformLoginPage.vue:707` 的 `TS18047: 't' is possibly 'null'` 阻断；该文件是工作区既有修改，和员工模块无关，未越界处理。

## 隔离、权限与清理

- 仅使用进程内 H2（`jdbc:h2:mem:`）与浏览器 API mock；未连接 QA MySQL、Docker、局域网或生产，未读取或输出密钥。
- H2 测试进程退出后无持久业务数据、会话、权限覆盖或测试审计残留；Chromium context 与 mock 状态已释放。
- 本次启动的本地 Vite 预览 `127.0.0.1:5174` 已关闭；未触碰其他端口。无本次 Playwright 结果文件需要删除。
- 未修改 `g3-gate-ledger.md`，未提交代码，未处理工作区其他既有修改。

## 解除阻断建议

1. 仅修复 EMP-03 考试弹层与桌面侧栏的层级/点击遮挡，并以真实 1280px 点击完成考试回归。
2. 由对应模块维护者处理 V77 与启动引导版本常量的基线一致性、以及 `PlatformLoginPage.vue` 可空值类型错误后，重新执行全量后端和前端生产验证。
3. 上述三项全部通过后，才可重新判定 EMP-01～EMP-04；本轮不进入 INS/QMAI/AI/GOV/FLOW。
