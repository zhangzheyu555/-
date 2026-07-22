# G4 FLOW-04～FLOW-06 候选 API 只读审计

执行时间：`2026-07-21`
范围：仅静态阅读候选后端、迁移与既有自动化测试。未启动服务、未连接任何数据库、未调用 API，也未读取 `.env` 或任何密钥。

## 结论

三个流程均有候选 API、租户参数传递和可复用的 H2/Mock 自动化基础，但当前仅为 **可实施性审计**，不是 G4 通过证据。

真实 QA 闭环仍依赖：经验证可登录的本地受控账号、至少一个同租户合成品牌和门店，以及由候选 API 建立的业务夹具。当前 G4 已知认证阻断未解除前，不得执行下列写入步骤。

| 流程 | 静态可行性 | 主要阻断/前置 |
| --- | --- | --- |
| FLOW-04 财务经营闭环 | 可实施 | 需 FINANCE、BOSS、同店 STORE_MANAGER；门店必须已绑定合成品牌。 |
| FLOW-05 巡检整改复核 | 可实施 | 需 STORE_MANAGER、SUPERVISOR 及一条“不合格/红线”巡检记录；整改提交强制至少一张经认证上传的附件。 |
| FLOW-06 培训考试 | 可实施 | 需 SUPERVISOR、EMPLOYEE、BOSS/STORE_MANAGER；督导需有效 `EXAM` 门店范围与 `exam.manage`，员工需绑定同一门店。 |

## FLOW-04：FIN-A → BOSS-A → SM-A

### 候选接口与最小字段

| 步骤 | 候选接口 | 权限/范围 | 最小请求或断言 |
| --- | --- | --- | --- |
| 财务保存月度经营数据 | `PUT /api/finance/entries` | `finance.profit.write` 且角色硬上限为 `BOSS`/`FINANCE`；门店在 `FINANCE` 数据范围内 | `storeId`、`month`、`brandId`，以及全部金额字段（`sales` 至 `expOther`）使用两位小数；`note` 可为空。 |
| 老板全局读取 | `GET /api/finance/dashboard?month=&brandId=`、`GET /api/finance/entries?month=&brandId=` | `finance.profit.read`；BOSS 的数据范围应为全量 | 返回含该合成门店；核验 `income`、`costSum`、`expenseSum`、`net`、`margin` 均来自 `BigDecimal` 计算。 |
| 店长只读本店 | `GET /api/finance/entries/detail?month=&storeId=` | `finance.profit.read`，`FinanceService` 将店长无参/同店读取归一到绑定门店，其他门店为 `403` | 同店读到保存记录；伪造其他 `storeId` 为 `403`；对 `PUT /api/finance/entries` 为 `403`。 |
| 财务导出 | `GET /api/export/profit-ranking.csv?month=&brandId=&storeId=` | `requireDataExport` 后以 `BusinessScopeResolver` 解析 `FINANCE` 范围 | CSV 仅含授权范围、字段金额与读取一致；成功下载只新增导出审计，不改写经营数据。 |

### 审计与可复用自动化

- `FinanceService.save` 调用 `FinanceRepository.logSave` 写入 `operation_log`（目标 `profit_entry`，含租户、门店和月份）。
- `ExportController` 成功导出写 `data_export` 操作日志；非法月份和范围拒绝也有审计路径。
- 可复用测试：`backend/src/test/java/com/storeprofit/system/finance/FinanceServiceTest.java`、`FinanceDataScopeRepositoryTest.java`、`FinanceRepositoryCalculationTest.java`、`ProfitEntryRequestValidationTest.java`、`backend/src/test/java/com/storeprofit/system/reporting/ExportControllerScopeTest.java`、`ProfitCsvRoundTripTest.java`。

### 真实 QA 最小夹具与断言

1. 用候选 API 在已存在的合成品牌下创建门店 A，并创建/授权 FINANCE 与绑定该门店的 STORE_MANAGER；BOSS 全量账号仅读取/管理。
2. 财务保存一个未被既有数据使用的合成月份；以 API 响应和只读 SQL 对同一 `tenant_id`、`store_id`、`month` 比对金额与一条保存审计。
3. BOSS 全局读、店长同店读、店长跨店 `403`、店长写 `403`、匿名 `401`，随后导出并核验 CSV 范围及下载审计。
4. 清理由候选 API 删除经营记录、停用/删除测试账号和门店；仅在全部流程结束后按基线脚本恢复。

## FLOW-05：SM-A → SUP-A → SM-A → SUP-A/BOSS-A

本流程选择计划中允许的“巡检问题 → 整改 → 复核”分支；它不产生库存流水，不能用日报损的库存影响口径替代或声称库存变化。

### 候选接口与最小字段

| 步骤 | 候选接口 | 权限/范围 | 最小请求或断言 |
| --- | --- | --- | --- |
| 店长查看/上传整改证据 | `GET /api/inspections/rectifications/mine`；`POST /api/inspections/{recordId}/rectification/evidence`（multipart） | `inspection.read`、`todo.transition`；角色硬上限为 BOSS/STORE_MANAGER，且必须在 `INSPECTION` 门店范围 | `file` 为合成图片/文件；返回附件 ID 与受认证下载路径。 |
| 店长提交整改 | `POST /api/inspections/{recordId}/rectification` | 同上；记录必须属于本店且为不合格或有红线 | `note` 非空，`attachmentIds` 至少一个且必须属于该整改流程。 |
| 督导复核 | `GET /api/inspections/rectifications/reviews`；`POST /api/inspections/{recordId}/rectification/review` | `inspection.manage`；角色硬上限为 BOSS/SUPERVISOR，且在 `INSPECTION` 门店范围 | `decision` 为 `APPROVED` 或 `REJECTED`，`note` 非空；第二次复核为 `409`，不得覆盖首次结论。 |

### 审计与可复用自动化

- `InspectionRectificationService` 对证据上传、整改提交、通过/驳回复核均写 `operation_log`，同时保留 `inspection_rectification_action` 状态轨迹。
- 可复用测试：`backend/src/test/java/com/storeprofit/system/inspection/InspectionRectificationWorkflowH2Test.java` 已覆盖复核成功审计、二次复核冲突、跨店 `403`、跨租户不可见；`InspectionRectificationControllerTest.java` 已覆盖匿名 `401` 与服务层 `403` HTTP 映射。
- 仍需真实 QA 补齐完整的“创建不合格巡检记录 → 上传附件 → 提交 → 督导复核”链，以及附件下载认证头、店长跨店、督导跨店/跨租户和桌面 `1280px` 证据。既有 `INS-01～INS-05` 报告曾指出这些 UI/附件回归缺口，不能以静态审计掩盖。

### 真实 QA 最小夹具与断言

1. 同一合成门店 A 下创建/确认 SM-A、SUP-A，并由允许角色通过候选 API 创建一条带问题的合成巡检记录（或使用专门、可恢复的 G4 合成基线）。
2. SM-A 上传合成整改附件并提交；核验记录状态 `PENDING_REVIEW`、附件所有权和提交审计。
3. SUP-A 仅在获授权范围内看到该记录并 `APPROVED`；核验状态、审计、动作轨迹与重复复核 `409`。BOSS-A 仅做全局结果读取（若使用）。
4. 认证附件读取成功，匿名为 `401`、未授权/跨店为 `403`；清理附件、整改记录、巡检夹具及其测试审计。

## FLOW-06：SUP-A → EMP-A → BOSS-A/SM-A

### 候选接口与最小字段

| 步骤 | 候选接口 | 权限/范围 | 最小请求或断言 |
| --- | --- | --- | --- |
| 督导创建试卷 | `POST /api/exam-center/papers` | `exam.manage` 且可管理的 `EXAM` 范围 | `paperCode`、`paperName`、`roleScope=EMPLOYEE`、`passScore`、`enabled`、至少一道题；题含 `questionType`、`questionText`、`standardAnswer`、`score`。 |
| 督导发布 | `POST /api/exam-center/campaigns` | 同上；目标门店不能越出督导范围 | `paperId`、`title`、有效 `startAt`/`dueAt`、`storeIds`、`targetRoles=["EMPLOYEE"]` 或明确 `userIds`。 |
| 员工答题交卷 | `GET /api/exam-center/assignments/{assignmentId}/paper`；`POST /api/exam-center/assignments/{assignmentId}/submit` | `exam.learn` 且仅本人任务 | `violated=false`，`answers` 中每题的 `questionId`/`userAnswer`；重复提交返回同一不可变 attempt，不新建记录/审计。 |
| 进度/成绩读取 | `GET /api/exam-center/results`；`GET /api/exam-center/campaigns/{campaignId}`；`GET /api/boss/exam-summary` | 员工仅本人；店长按其 `EXAM` 门店范围；BOSS 需系统概览权限 | EMP 仅一条本人结果；SM 只见本店；BOSS 汇总含该门店完成数、完成率和风险。 |

### 审计与可复用自动化

- `ExamCenterService` 对建卷、发布、交卷、查看/导出成绩写 `operation_log`；`ExamLearningService.review` 对人工阅卷写审计；`BossExamSummaryService.summary` 对老板概览写审计。
- `backend/src/test/java/com/storeprofit/system/operations/ExamLearningFlowTest.java` 是最接近的 H2 闭环：督导创建题材/试卷、发布、员工交卷、重复交卷不新增 attempt、督导阅卷、员工读取结果。
- `ExamCenterServiceAccessTest.java` 覆盖员工仅本人、店长仅本店、BOSS 建卷及跨范围拒绝；`BossExamSummaryServiceTest.java` 覆盖 BOSS 汇总/审计和财务拒绝。

### 真实 QA 最小夹具与断言

1. 在合成门店 A 创建/确认 SUP-A、EMP-A、SM-A 与 BOSS-A；SUP-A 必须有该门店 `EXAM` 范围，EMP-A/SM-A 绑定门店 A。
2. 督导用一道自动判分题创建试卷并对 EMP-A 发布；员工取题、交卷两次，核验 `training_exam_attempt` 仅一条且首次提交审计仅一条。
3. EMP-A 读取仅自身成绩；SM-A 的考试中心/成绩接口仅见门店 A；BOSS-A 读取 `/api/boss/exam-summary` 并核验完成进度及审计。
4. 最小拒绝集：匿名 `401`、员工伪造他人 assignment `403`、店长查看其他门店 campaign `403`、督导对范围外门店发布 `403`。完成后清理考试/题目/attempt/审计及账号夹具，恢复 G4 基线。

## 执行前统一阻断与最小解锁

1. **认证前置仍阻断**：现有 G4 记录显示本地 QA BOSS 登录为 `LOGIN_FAILED`。在受控、非敏感凭据路径恢复并验证登录前，禁止进入真实写流程。
2. **账号/数据范围前置**：当前 G4 仅确认 BOSS 和合成品牌基线；FLOW-04～06 都还需经候选 API 创建的 FINANCE、STORE_MANAGER、SUPERVISOR、EMPLOYEE 及对应门店/范围。
3. **顺序建议**：认证解锁后先完成 FLOW-01 建立门店 A/角色夹具，再按 FLOW-04、FLOW-05、FLOW-06 执行。每条流程单独记录 HTTP、业务表、`operation_log`、拒绝边界、清理和桌面证据；任一失败即停止该依赖链。

本文件未改变业务代码、迁移、权限、测试断言或 G3/G4 通过结论。
