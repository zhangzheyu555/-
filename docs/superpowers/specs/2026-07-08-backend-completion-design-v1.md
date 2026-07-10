# AI Profit OS 后端补全设计 v1

日期：2026-07-08  
状态：持续更新；P0 迁移能力已完成，P1 薪资/费用/巡店记录 API 第一版已实现  
适用项目：AI Profit OS / AI 企业经营分析系统  
当前主线：Spring Boot 后端托管 `index.html`，MySQL 作为真实业务数据的目标存储

## 1. 目标

把当前系统从“旧前端 + 兼容 KV 存储 + 部分结构化 API”的过渡状态，逐步补齐为“后端结构化业务 API + MySQL 唯一真实数据源 + 角色/租户权限统一校验”的可上线架构。

第一阶段不追求一次性重写全部页面，而是先把后端真实能力补完整，再按模块把前端从旧的浏览器存储和兼容 KV 迁移到结构化 API。

## 2. 当前判断

当前后端已经具备基础骨架：

- Spring Boot 应用可以托管前端静态页面。
- Flyway 已有 V1 到 V4 数据库迁移。
- 已有认证、会话、组织门店、财务、导入、巡店、仓库、AI 助手、审计、兼容存储等模块。
- MySQL 已经作为本地运行时数据库使用。
- 浏览器本地存储已开始收紧，真实业务数据应迁移到后端。

但系统仍处于过渡状态：

- 结构化业务 API 没有完全覆盖所有真实业务数据。
- `kv_storage` 仍承担兼容作用，不应作为长期目标数据模型。
- 一些表已存在，但缺少完整 Controller / Service / 测试。
- 前端仍有部分页面逻辑围绕旧数据形态运行。
- 多租户、角色范围、门店范围需要更多端到端验证。

## 3. 设计原则

1. MySQL 是真实业务数据的唯一长期来源。
2. 浏览器本地存储只允许保存 UI 状态、临时草稿或显式迁移前缓存，不保存真实经营数据。
3. `kv_storage` 只作为兼容层和迁移缓冲区，不作为新功能的目标存储。
4. 新增业务能力优先落到结构化表和结构化 API，不新增大 JSON 袋式存储。
5. 权限、租户、门店范围必须由后端强制校验，前端只负责展示和交互。
6. 虚拟数据、演示数据、种子数据不得混入真实客户数据。
7. 文档、代码、提交记录都不能包含密钥、数据库口令或其他敏感信息。
8. 每完成一个后端切片，同步更新本文档的“进度矩阵”和“决策记录”。

## 4. 后端现状与缺口矩阵

| 模块 | 当前状态 | 未补齐内容 | 优先级 |
| --- | --- | --- | --- |
| 认证与会话 | 已有登录、登出、当前用户接口、token 表 | 用户新增/编辑/禁用、密码变更、权限矩阵测试、前端旧账号逻辑切换 | P0 |
| 多租户基础 | 已有 `tenant` 表，核心表已加 `tenant_id` | `kv_storage` 未租户化，缺少跨租户隔离测试，缺少租户切换/管理接口 | P0 |
| 组织门店 | 已有品牌、门店列表、门店创建和更新 | 前端仍可能通过兼容存储维护门店，门店停用/恢复、门店范围校验需要补强 | P0 |
| 财务利润 | 已有月份、看板、利润录入、明细、CSV 导出 | 删除/批量修正、口径校验、前端完全切到结构化 API、统计测试不足 | P0 |
| 员工薪资 | 已有 `salary_record` 表；legacy KV `salary -> salary_record` 结构化迁移已实现；已新增 `/api/salaries` CRUD/list/summary 第一版和服务层/控制器测试 | 仍缺薪资计算规则、导入/导出、前端切换到正式薪资 API | P1 |
| 费用报销 | 已有 `expense_claim` 表；legacy KV `expenses -> expense_claim` 结构化迁移已实现；已新增 `/api/expenses` CRUD/list/submit/approve/reject 第一版和服务层/控制器测试 | 仍缺附件/凭证长期策略、前端切换到正式费用 API、审批查询分页和导出 | P1 |
| 督导巡店 | 已有巡店表、检测和导出入口；legacy KV `inspections -> inspection_record` 结构化迁移已实现；已新增 `/api/inspections` 记录 CRUD/list/detail 第一版和服务层/控制器测试；已新增 `GET /api/inspections/service-health` | 仍缺检测结果自动绑定巡店记录、巡店统计和前端切换 | P1 |
| 数据导入 | 已有利润表识别和提交接口 | 导入草稿持久化、截图/图片识别真实链路、重复月份冲突处理、审计记录 | P1 |
| 仓库 | 已有物料、库存批次、调拨申请、审核、出库 MVP | 采购 V1、供应商、采购单、入库单、低库存建议、成本分摊规则 | P2 |
| AI 助手 | 已有聊天接口和 DeepSeek/fallback 机制 | 后端上下文来源统一、回答引用数据口径、权限范围过滤、可测试的防幻觉机制 | P2 |
| 审计日志 | 已有审计日志查询能力；legacy KV `logs -> operation_log` 结构化迁移已实现 | 所有写操作统一审计、审计查询筛选和分页 | P1 |
| 兼容存储迁移 | 已有 `/api/storage` 兼容通道；已新增 `GET /api/migration/status`、`POST /api/migration/browser-storage/preview`、`POST /api/migration/browser-storage/run`、`GET /api/migration/legacy-kv/preview` 和 `POST /api/migration/legacy-kv/run` 第一版 | `legacy-kv/run` 目前支持 `stores -> store_branch`、`entries -> profit_entry`、`salary -> salary_record`、`expenses -> expense_claim`、`inspections -> inspection_record`、`logs -> operation_log`；后续进入 `/api/storage` 只读化和正式业务 API 替换 | P0 |
| 运维部署 | 已有健康检查接口 | MySQL/AI/YOLO readiness、备份恢复手册、运行参数检查、线上冷启动优化复核 | P1 |
| 自动化测试 | 已有少量应用、助手、导入、静态资源、存储策略测试 | 缺少模块级集成测试、租户隔离测试、权限回归测试、浏览器主流程测试 | P0 |

## 5. 目标后端边界

### 5.1 平台与权限

目标包边界：

- `platform.auth`：登录、登出、token、密码、角色。
- `platform.tenant`：租户识别、租户管理、租户隔离。
- `platform.users`：用户资料、角色、门店范围。
- `audit`：统一记录关键写操作。

后端必须保证：

- 所有业务查询都带租户约束。
- 门店经理只能访问授权门店。
- 财务、老板、管理员、督导的写入范围由后端判断。
- 禁止前端通过兼容接口写入账号、口令、token 等敏感键。

### 5.2 组织与门店

目标 API：

- `GET /api/brands`
- `GET /api/stores`
- `POST /api/stores`
- `PUT /api/stores/{id}`
- `POST /api/stores/{id}/disable`
- `POST /api/stores/{id}/restore`
- `GET /api/users/{id}/store-scope`
- `PUT /api/users/{id}/store-scope`

需要补齐：

- 门店状态流：启用、停用。
- 门店范围变更审计。
- 前端门店列表读取结构化 API，而不是读取兼容 KV。

### 5.3 财务、薪资、费用

财务利润目标 API：

- 保留已有 `GET /api/finance/months`
- 保留已有 `GET /api/finance/dashboard`
- 保留已有 `GET /api/finance/entries`
- 保留已有 `GET /api/finance/entries/detail`
- 保留已有 `PUT /api/finance/entries`
- 新增 `DELETE /api/finance/entries/{id}`
- 新增 `POST /api/finance/entries/batch-adjust`

薪资目标 API：

- 已实现 `GET /api/salaries`
- 已实现 `POST /api/salaries`
- 已实现 `PUT /api/salaries/{id}`
- 已实现 `DELETE /api/salaries/{id}`
- 已实现 `GET /api/salaries/summary`

费用目标 API：

- 已实现 `GET /api/expenses`
- 已实现 `POST /api/expenses`
- 已实现 `PUT /api/expenses/{id}`
- 已实现 `POST /api/expenses/{id}/submit`
- 已实现 `POST /api/expenses/{id}/approve`
- 已实现 `POST /api/expenses/{id}/reject`
- 已实现 `DELETE /api/expenses/{id}`

需要补齐：

- 费用审批状态机。
- 薪资和费用的门店、月份、租户约束。
- 薪资、费用、利润在报表和 AI 助手中的统一口径。

### 5.4 督导巡店

目标 API：

- 已实现 `GET /api/inspections`
- 已实现 `POST /api/inspections`
- 已实现 `GET /api/inspections/{id}`
- 已实现 `PUT /api/inspections/{id}`
- 已实现 `DELETE /api/inspections/{id}`
- 保留 `POST /api/inspections/detect`
- 保留 `POST /api/inspections/export`
- 已实现 `GET /api/inspections/service-health`

需要补齐：

- 巡店记录持久化。
- YOLO 检测结果与巡店记录绑定。
- 检测服务未配置或不可用时，后端 `GET /api/inspections/service-health` 返回明确 `UNCONFIGURED`/`DOWN` 状态，而不是让前端误以为识别成功。
- 导出内容以数据库记录为准。

### 5.5 导入与迁移

导入目标 API：

- 保留 `POST /api/imports/profit/recognize`
- 保留 `POST /api/imports/profit/{importId}/commit`
- 新增 `GET /api/imports`
- 新增 `GET /api/imports/{id}`
- 新增 `DELETE /api/imports/{id}`

迁移目标 API：

- 已实现：`GET /api/migration/status`
- 已实现：`POST /api/migration/browser-storage/preview`
- 已实现：`POST /api/migration/browser-storage/run`
- 已实现：`GET /api/migration/legacy-kv/preview`
- 已实现：`POST /api/migration/legacy-kv/run`，当前支持 `stores -> store_branch`、`entries -> profit_entry`、`salary -> salary_record`、`expenses -> expense_claim`、`inspections -> inspection_record`、`logs -> operation_log`

迁移要求：

- 迁移必须幂等。
- 迁移前必须能预览影响范围。
- 迁移后必须写审计日志。
- 真实业务数据迁移到结构化表后，兼容 KV 中对应键应进入只读或归档状态。

### 5.6 仓库采购 V1

仓库已有 MVP，下一阶段目标：

- `supplier`
- `purchase_order`
- `purchase_order_line`
- `warehouse_receipt`
- `warehouse_receipt_line`

目标 API：

- `GET /api/warehouse/suppliers`
- `POST /api/warehouse/suppliers`
- `GET /api/warehouse/purchase-orders`
- `POST /api/warehouse/purchase-orders`
- `POST /api/warehouse/purchase-orders/{id}/approve`
- `POST /api/warehouse/purchase-orders/{id}/receive`
- `GET /api/warehouse/low-stock-suggestions`

采购 V1 放在薪资、费用、巡店结构化之后做，避免核心经营数据仍在兼容层时继续扩张新业务。

### 5.7 AI 助手

目标不是让 AI 直接读前端状态，而是由后端统一整理上下文：

- 门店范围按当前用户过滤。
- 月份、利润、费用、薪资、巡店、库存都来自结构化 API 或服务层。
- AI 回答要带数据来源标识，例如月份、门店范围、数据表来源。
- DeepSeek 不可用时返回明确降级提示。

后续可新增：

- `GET /api/assistant/context-preview`
- `POST /api/assistant/tasks`
- `GET /api/assistant/tasks/{id}`

## 6. 数据迁移路线

### 阶段 A：浏览器数据进入后端

当前已开始执行：

- 浏览器不再作为真实业务数据长期存储位置。
- 前端应通过迁移按钮或自动流程，把旧浏览器数据提交到后端。
- 后端按角色和键名限制兼容存储写入。

仍需完成：

- 明确哪些浏览器键属于真实业务数据。
- 给出迁移结果报告。
- 迁移成功后清理或忽略旧本地数据。

### 阶段 B：兼容 KV 进入结构化表

目标：

- `stores` 进入 `store_branch`。
- `entries` 进入 `profit_entry`。
- `salary` 进入 `salary_record`。
- `expenses` 进入 `expense_claim`。
- `inspections` 进入 `inspection_record`。
- `logs` 进入 `operation_log`。

要求：

- 每类数据都需要预览、运行、回滚建议。
- 重复数据按业务键去重，例如租户、月份、门店、类型。
- 无法自动映射的数据进入异常报告，不静默丢弃。

### 阶段 C：兼容 KV 只读化

完成结构化迁移后：

- `/api/storage` 只允许读取历史兼容数据。
- 新写入只允许 UI 设置、迁移备份等非真实业务数据。
- 文档和 README 删除“浏览器本地存储作为兜底”的旧描述。

## 7. 分阶段实施计划

### P0：真实数据后端化和安全边界

目标：先防止真实数据继续散落在浏览器和兼容 KV。

任务：

- 整理前端真实业务键清单。
- 已完成迁移状态接口：`GET /api/migration/status`。
- 已完成 legacy KV 迁移预览接口：`GET /api/migration/legacy-kv/preview`。
- 已完成浏览器存储迁移预览接口：`POST /api/migration/browser-storage/preview`。
- 已完成浏览器存储迁移运行接口：`POST /api/migration/browser-storage/run`。
- 已完成 legacy KV 运行接口第一版：`POST /api/migration/legacy-kv/run`，支持 `stores -> store_branch`、`entries -> profit_entry`、`salary -> salary_record`、`expenses -> expense_claim`、`inspections -> inspection_record`、`logs -> operation_log`，并写入迁移审计日志；`entries`、`salary`、`expenses` 和 `inspections` 中门店不存在的记录会跳过；`logs` 使用稳定 `target_id` 跳过重复导入。
- 给 `/api/storage` 增加更明确的业务键限制和只读策略。
- 补充租户、角色、门店范围的关键测试。
- 更新 README 中关于本地存储的旧说明。

验收：

- 新增真实业务数据不写入浏览器本地存储。
- 兼容写入被后端按角色和键名拦截。
- 迁移流程可预览、可审计、可重复执行。

### P1：薪资、费用、巡店结构化 API

目标：把已有表但缺少完整接口的核心业务补齐。

任务：

- 已实现薪资 CRUD 和汇总第一版：`GET/POST/PUT/DELETE /api/salaries` 与 `GET /api/salaries/summary`，写操作进入审计日志。
- 已实现费用 CRUD、提交、审批、驳回第一版：`GET/POST/PUT/DELETE /api/expenses` 与 `submit/approve/reject`，状态流为“草稿 -> 待审核 -> 已完成/已驳回”，写操作进入审计日志。
- 已实现巡店记录 CRUD/list/detail 第一版：`GET/POST/PUT/DELETE /api/inspections` 与 `GET /api/inspections/{id}`，支持日期、门店、品牌、是否通过过滤，写操作进入审计日志。
- 已实现巡店识别服务健康检查：`GET /api/inspections/service-health` 返回 `UP`、`DOWN` 或 `UNCONFIGURED`，主系统不因 YOLO 服务不可用而失败。
- 实现巡店检测结果关联。
- 所有写操作进入审计日志。

验收：

- 前端对应模块可以不依赖兼容 KV 完成新增、查询、修改、删除。
- 不同角色访问范围符合预期。
- 服务层测试覆盖主要状态流。

### P2：前端按模块切换到结构化 API

目标：逐个模块替换旧数据路径。

顺序建议：

1. 门店。
2. 利润录入。
3. 薪资。
4. 费用。
5. 巡店。
6. 日志。

验收：

- 真实业务数据读取和写入均走结构化 API。
- 旧浏览器数据只用于一次性迁移。
- 页面刷新、换浏览器、换设备后数据一致。

### P3：仓库采购 V1

目标：在仓库 MVP 基础上补采购闭环。

任务：

- 供应商管理。
- 采购单。
- 审批。
- 到货入库。
- 低库存建议。

验收：

- 采购、入库、库存批次、库存流水能串起来。
- 采购数据可被 AI 助手和经营报表引用。

### P4：AI 助手可信数据上下文

目标：AI 回答基于后端结构化数据，不依赖前端临时状态。

任务：

- 后端统一构造助手上下文。
- 增加上下文预览接口。
- 增加回答数据来源说明。
- 增加降级和错误状态测试。

验收：

- AI 对不同角色看到的数据范围不同。
- 回答能说明使用了哪些月份、门店和数据类型。
- AI 不可用时系统仍可给出确定性的降级分析。

### P5：商业化多租户硬化

目标：让系统具备更可靠的线上交付能力。

任务：

- 租户管理接口。
- 租户级配置。
- 数据备份和恢复手册。
- 线上 readiness 检查。
- 性能和冷启动优化复核。

验收：

- 新租户可初始化。
- 租户数据隔离有自动化测试。
- 部署后能通过运维检查确认依赖可用。

## 8. 测试与验证策略

每个后端切片至少包含：

- Service 单元测试：业务规则、状态流、权限分支。
- Repository 或集成测试：关键查询、租户条件、唯一性。
- Controller 测试：认证、角色、请求校验、错误码。
- 浏览器冒烟：核心页面打开、登录、读写、刷新后数据仍在。
- 数据库验证：Flyway 迁移可在本地 MySQL 正常执行。

关键回归用例：

- 老板可以查看全部门店。
- 门店经理只能查看授权门店。
- 督导只能写巡店相关数据。
- 财务可以写利润、薪资、费用，不能写账号敏感数据。
- 未登录用户不能访问业务 API。
- 跨租户数据不能被查询、修改、导出。

## 9. 文档更新规则

后续实现过程中，每完成一个切片，必须更新：

- 第 4 节“后端现状与缺口矩阵”的状态。
- 第 7 节“分阶段实施计划”的任务完成情况。
- 第 11 节“决策记录”。
- 如接口有变化，同步更新对应目标 API 列表。

如果实现中发现原设计不合理，先更新文档写明原因，再改代码。

## 10. 当前开放问题

1. 是否接受 `kv_storage` 在 P0 后进入“真实业务键只读”模式？建议接受。
2. 薪资、费用、巡店是否优先于仓库采购 V1？建议优先，因为它们属于当前已有真实业务数据闭环。
3. 导入草稿是否需要长期保存？建议保存，便于追溯识别结果和人工修正。
4. 是否需要先做后台管理式用户/权限页面？建议后端先补接口，前端页面可在 P2/P5 之间安排。
5. YOLO 服务是否作为主应用必需依赖？建议不是必需依赖，未配置时主系统可运行，但巡店识别返回明确不可用状态。

## 11. 决策记录

| 日期 | 决策 | 原因 |
| --- | --- | --- |
| 2026-07-08 | 第一版以后端结构化 API 和 MySQL 唯一真实数据源为主线 | 用户明确要求真实数据不要存浏览器，要存 MySQL |
| 2026-07-08 | 先补 P0/P1，再扩展仓库采购和 AI Agent | 先收拢现有真实业务数据，降低继续扩张造成的数据分散风险 |
| 2026-07-08 | `kv_storage` 定位为兼容和迁移缓冲，不作为新功能目标存储 | 当前系统需要平滑迁移，但长期必须回到结构化表 |
| 2026-07-08 | 新增只读 `GET /api/migration/status` 作为 P0 第一批后端能力 | 先可视化 legacy KV 中真实业务键残留情况，再做迁移预览和运行，避免盲目改数据 |
| 2026-07-08 | 新增只读 `GET /api/migration/legacy-kv/preview` | 先展示业务键到结构化表的迁移映射，不执行写库，给后续 `run` 接口提供可验证输入 |
| 2026-07-08 | 新增只读 `POST /api/migration/browser-storage/preview` | 前端提交 localStorage 快照时，后端先分类业务数据、敏感账号键、兼容元数据和未知键，不写库，降低迁移误伤风险 |
| 2026-07-08 | 新增 `POST /api/migration/browser-storage/run` | 复用预览分类，只把业务键写入 MySQL 兼容层，敏感账号键和未知键不写入，为后续结构化迁移创造统一入口 |
| 2026-07-08 | 新增 `POST /api/migration/legacy-kv/run` 第一版 | 先支持 `stores -> store_branch` 和 `entries -> profit_entry` 幂等迁移和审计；费用、巡店等复杂业务键暂不假迁移，避免数据误写 |
| 2026-07-08 | 扩展 `POST /api/migration/legacy-kv/run` 支持 `salary -> salary_record` | 前端旧工资数据字段与 `salary_record` 表可稳定映射，迁移时按门店存在性过滤并写入审计，先解决真实薪资数据落结构化 MySQL |
| 2026-07-08 | 扩展 `POST /api/migration/legacy-kv/run` 支持 `expenses -> expense_claim` | 前端旧报销数据可映射为费用凭证，迁移时规范化 `pending/done` 状态并保留金额、类别、事由和截图引用 |
| 2026-07-08 | 扩展 `POST /api/migration/legacy-kv/run` 支持 `inspections -> inspection_record` | 前端旧巡店记录可映射为巡店表，迁移时计算得分、保留扣分/红线/照片 JSON，并根据红线判定通过状态 |
| 2026-07-08 | 扩展 `POST /api/migration/legacy-kv/run` 支持 `logs -> operation_log` | 前端旧操作日志可导入服务端审计表，迁移时保留旧操作者、业务对象、门店/月、前后 JSON 和原因，并用稳定 `target_id` 避免重复导入 |
| 2026-07-08 | 新增 `/api/salaries` 正式薪资 API 第一版 | 让薪资真实业务数据可以直接读写 `salary_record`，按租户、月份、门店和品牌过滤；老板/管理员/财务可写，店长只读本店，写操作进入 `operation_log` |
| 2026-07-08 | 新增 `/api/expenses` 正式费用 API 第一版 | 让报销真实业务数据可以直接读写 `expense_claim`，按租户、月份、门店、品牌和状态过滤；店长只能处理本店，老板/管理员/财务可审核，状态流和写操作审计由后端强制执行 |
| 2026-07-08 | 新增 `/api/inspections` 正式巡店记录 API 第一版 | 让巡店真实业务数据可以直接读写 `inspection_record`，按租户、日期范围、门店、品牌和是否通过过滤；督导/老板/管理员可写，店长只读本店，保留现有检测和导出入口 |
| 2026-07-08 | 新增 `GET /api/inspections/service-health` | 后端通过识别服务同源 `/health` 做轻量探测，返回 `UP`/`DOWN`/`UNCONFIGURED`，使前端和运维能区分主应用可用与 YOLO 服务不可用 |

## 12. 下一步建议

确认本文档后，进入实施计划拆分。建议第一批代码任务为：

1. 为 `/api/storage` 增加业务键只读/迁移态策略。
2. 设计检测结果绑定巡店记录的保存链路。
3. 补租户、角色、门店范围的关键后端测试。
4. 将前端薪资、费用、巡店模块切到 `/api/salaries`、`/api/expenses`、`/api/inspections`，让真实数据直接进入 MySQL 结构化表。
## 13. 2026-07-08 巡店检测结果绑定保存链路 v1

### 13.1 目标

把 YOLO/FastAPI 返回的单张或多张图片检测结果，绑定到已经存在的 `inspection_record`，并由后端规范化写入 MySQL。前端可以继续把 `/api/inspections/detect` 作为临时识别入口，但真实巡店结果必须通过正式业务 API 保存到数据库，不再依赖浏览器本地存储。

### 13.2 第一版范围

- 新增 `POST /api/inspections/{id}/detection-results`。
- 请求体接收检测结果数组，以及可选的巡检人、品牌、满分、备注覆盖值。
- 后端读取当前租户下的巡店记录，保留原记录的门店和日期，只更新检测相关字段。
- `photos_json` 保存图片级检测摘要：图片 ID、文件名、是否通过、识别状态、检测数量、摘要、必要时的标注图 data URL。
- `deductions_json` 保存扣分项：图片 ID、文件名、扣分项目、扣分内容、扣分值。
- `redlines_json` 保存不合格/红线项：图片 ID、文件名、自动状态、检测数量、检测摘要、原始 detections。
- `score` 由 `full_score - 扣分绝对值合计` 计算，最低为 0。
- `passed` 由全部图片是否通过、是否存在红线项共同推断。
- 写操作继续进入 `operation_log`，action 使用 `inspection_detection_bind`。

### 13.3 权限和失败策略

- 只有 `ADMIN`、`BOSS`、`SUPERVISOR` 可以绑定检测结果。
- 店长保持只读，不能写入检测结果。
- 绑定接口必须按 `tenant_id` 查找记录，跨租户记录表现为未找到。
- 空检测结果、非法分数、非法记录 ID 返回明确业务错误，不静默写入空数据。
- 第一版不新增文件对象存储；标注图如需持久化，暂存在 `photos_json` 中。后续如果图片量变大，再把图片迁到对象存储，只在 MySQL 保存 URL 和摘要。

### 13.4 验收

- 能把检测结果绑定到已有巡店记录，并从 `GET /api/inspections/{id}` 读回。
- 绑定后 `score`、`passed`、`deductions_json`、`redlines_json`、`photos_json` 都由后端计算/规范化。
- 店长绑定会被拒绝。
- 全量后端测试通过。

### 13.5 实现记录

第一版已实现 `POST /api/inspections/{id}/detection-results`。后端会读取当前租户下已有巡店记录，把检测结果规范化为 `photos_json`、`deductions_json`、`redlines_json`，重新计算 `score` 和 `passed`，并写入 `operation_log`。对应测试覆盖了主管绑定、店长拒绝、空结果拒绝和控制器认证包装。

## 14. 2026-07-08 权限边界修正记录

### 14.1 费用报销店长更新边界

发现费用报销草稿更新时，服务层只校验了请求体中的目标门店，没有先校验已有报销单原本所属门店。第一版修正为：店长更新已有费用单前，必须同时满足“原费用单属于本店”和“更新后的门店仍属于本店”。这样可以防止店长通过已知其他门店草稿 ID，把别的门店费用单改写到自己门店名下。

已补充回归测试：`ExpenseServiceTest.storeManagerCannotWriteOrReadOtherStores` 覆盖店长无法创建其他门店费用，也无法更新其他门店已有费用单。

## 15. 2026-07-08 分角色今日待办接口 v1

### 15.1 目标

前端 `今日待办` 工作台已经按角色调用独立接口，但后端缺少 `/api/boss/todos` 等路由，导致页面进入时返回 404。第一版后端补齐 6 个接口，并且只从 MySQL 结构化业务表生成待办，不写入浏览器、不生成虚拟待办。

### 15.2 接口范围

- `GET /api/boss/todos`
- `GET /api/finance/todos`
- `GET /api/supervisor/todos`
- `GET /api/store-manager/todos`
- `GET /api/warehouse/todos`
- `GET /api/operations/todos`

所有接口支持 `includeDone`、`status`、`limit`、`brandId`、`storeId` 查询参数，响应字段遵循角色待办生命周期合同。

### 15.3 数据来源

第一版只接入已经结构化且能稳定推导的待办：

- `inspection_record.passed = 0` 生成 `RISK` 待办，来源模块为 `督导巡店`，跳转目标为 `inspect`。
- `store_requisition.status in ('SUBMITTED', 'APPROVED')` 生成 `PENDING` 待办，来源模块为 `仓库叫货`，跳转目标为 `warehouse`。
- `profit_entry` 中净利润小于 0，或收入大于 0 且净利率低于 8% 的记录生成财务 `RISK` 待办，来源模块为 `利润表`，跳转目标为 `report`。
- `expense_claim.status = '待审核'` 的记录生成财务 `PENDING` 待办，来源模块为 `报销`，跳转目标为 `expense`。

运营接口第一版返回稳定空结构，不为了展示效果编造运营待办。工资待核对暂不生成待办，因为 `salary_record` 当前没有审核状态字段。

### 15.4 权限和范围

- `ADMIN`、`BOSS` 可以预览所有角色待办。
- 普通角色只能访问自己的角色接口。
- 店长接口强制使用登录用户绑定的 `storeId`，忽略前端传入的 `storeId` 和 `brandId`，防止越权查看其他门店。
- 所有查询都带 `tenant_id`，跨租户记录不会进入响应。
- 财务、督导、仓库待办支持通过 `POST /api/{role}/todos/{todoId}/escalate` 上报老板；上报原因 `reason` 必填，严重级别只允许 `RISK` 或 `PENDING`，写入 `todo_escalation` 后由老板待办接口读取未处理上报事项。
- 前端 `今日待办` 只对财务、督导、仓库角色显示 `上报老板` 按钮；点击后必须填写原因，提交成功后刷新后端待办。利润异常本身不等于“已上报老板”，只有 `todo_escalation` 记录存在时才视为已上报。

### 15.5 测试记录

已新增 `RoleTodoServiceTest` 覆盖租户隔离、角色数据源隔离、店长门店锁定、老板跨角色预览、无权限访问拒绝、财务真实待办生成、待办上报老板。已新增 `RoleTodoControllerTest` 覆盖 6 个路由到角色枚举的映射、认证用户传递、查询参数包装和财务上报路由。已扩展 `RoleTodoWorkbenchStaticTest` 锁定前端上报入口、必填原因、`encodeURIComponent(todoId)` 和分角色上报接口路径。
