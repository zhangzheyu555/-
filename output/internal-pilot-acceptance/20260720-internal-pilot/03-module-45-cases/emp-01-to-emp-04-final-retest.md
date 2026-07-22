# EMP-01～EMP-04 员工与培训最终隔离复验

执行时间：2026-07-21（Asia/Shanghai）
执行范围：仅解除 EMP-03 桌面考试弹窗层叠阻断，并复跑 EMP 定向 H2 与桌面 Chromium；未执行全量 Maven、打包、前端类型检查或生产构建。

## 结论

**PASS（EMP-01～EMP-04 的本轮阻断已解除）**。考试作答遮罩已从 `app-main` 的独立层叠上下文传送至 `body` 顶层，固定桌面侧栏不再遮挡或截获答题控件。未改变考试权限、接口、答案提交或数据范围。

## 修改与直接回归

- `ExamCenterPage.vue`：仅将“考试作答”遮罩使用 Vue `Teleport` 传送至 `body`；遮罩仍使用既有 `--ds-z-modal` 层级、表单和提交逻辑。
- `35-employee-training-desktop.spec.ts`：在员工考试流程中增加桌面层叠断言，确认考试遮罩是 `body` 的直接子元素，并确认“立即上报”单选控件的命中点不属于 `nav.sidebar-navigation`。
- Chromium：`35-employee-training-desktop.spec.ts --project=chromium`，**3/3 通过**。
  - 店长新增员工、创建账号、办理离职与重复失败反馈；
  - 员工仅见本人资料、完成指派考试并提交；
  - 员工与店长即使携带陈旧考试管理权限仍不能进入督导考试管理。
- 在 1280×900 桌面视口下，考试选择与提交成功，页面无整体横向溢出、无控制台错误。

## H2 定向证据

命令：

```bash
cd backend && mvn -q -Dtest=EmployeeServiceAuditH2Test,EmployeeServicePermissionTest test
```

结果：**5/5 通过，0 failure，0 error，0 skipped**。

- 成功新增、修改、离职、创建/关联账号的审计覆盖，以及敏感字段不进入审计快照；
- 失败回滚无成功审计；跨店/跨租户拒绝为 403 并记录拒绝审计；未知对象保持 404；读取范围保持既有边界。

## 隔离与清理

- 后端为随机 `jdbc:h2:mem:` 数据源，测试进程结束即释放；未连接 QA MySQL、Docker、局域网或生产。
- 浏览器接口均为路由 mock 的合成数据；本轮 Vite 仅监听 `127.0.0.1:18175`，Chromium 完成后已停止。
- 未修改 `g3-gate-ledger.md`，未提交代码；未执行移动端/WebKit。

## 未在本轮重复的已有证据

上一轮已经记录的后端全量、打包、`vue-tsc -b` 与生产构建不在本轮重复执行；本轮只补足阻断所需的定向 H2 与 1280px Chromium 回归。
