# QA 最小可重复测试数据计划

## 原则

- 仅用于名称包含 `qa` 或 `test` 的独立 MySQL 数据库；禁止连接、重建或写入 3307 历史库和生产库。
- 所有账号、图片、商品和外部平台数据均为无敏感信息的测试数据。脚本不得保存或打印密码、Token、密钥。
- 初始化脚本必须幂等：以稳定业务编码查找并更新，重复执行不新增重复单据、附件或待办。
- 不创建生产样例数据；QA 基础数据与运行时业务验收数据分离，运行时数据使用唯一前缀和可追溯测试批次号。

## 基础数据集 `QA-45-BASE`

| 数据域 | 最小数据 | 关键用途 |
| --- | --- | --- |
| 租户 | 1 个 `qa-45-tenant` | 所有多租户与日志断言 |
| 品牌与门店 | 1 品牌、3 门店：`QA-S01`、`QA-S02`、`QA-S03` | 本店、授权门店、跨店 403 |
| 账号 | BOSS、FINANCE、SUPERVISOR、WAREHOUSE、OPERATIONS、EMPLOYEE 各 1；STORE_MANAGER 至少 2，分别绑定 `QA-S01`、`QA-S02` | 七类角色与跨店断言 |
| 仓库与物料 | 中央仓、区域仓、门店库存；active 商品 3 条、inactive 商品 1 条 | 叫货、入库、发货、停用限制、库存预警 |
| 报损配置 | active 品类至少 3、inactive 品类 1；包含克和个单位 | 分类选择、单价快照、无效品类校验 |
| 报销与附件 | 两店草稿/已提交报销、无敏感 PNG/JPEG 收据 | 图片必填、预览、附件鉴权 |
| 巡检 | 标准、任务、问题、整改和待复核记录 | 巡检、整改、复核 |
| 员工与培训 | 每店至少 2 员工、课程、题目、分配和进度 | 本人资料、考试、进度管理 |
| 财务与工资 | 两个月、两门店的利润/工资快照 | 数据范围、BigDecimal、导出 |
| 待办 | 报销、报损、巡检、仓库各一条源业务待办 | FLOW-01 闭环 |
| 外部平台 | Mock、空配置或专用测试凭证 | 不触发真实生产账号 |

## 运行时数据集与断言

| 批次 | 创建方式 | 断言后清理 |
| --- | --- | --- |
| `QA45-DL-*` | 店长创建日报损并上传无敏感图片 | 不自动删除；按批次查询日报损、明细、附件和 `operation_log` |
| `QA45-EXP-*` | 店长创建报销和附件 | 验证金额、状态、附件范围、审批日志 |
| `QA45-WH-*` | 叫货、发货、收货、退货 | 验证状态、库存流水守恒、下载日志 |
| `QA45-INS-*` | 巡检、整改、复核 | 验证问题/证据绑定和复核不可覆盖 |
| `QA45-EMP-*` | 员工、课程、答题记录 | 验证员工与门店范围 |

## 可复用现有脚本

- `scripts/qa/Initialize-QAReleaseFixtures.ps1`：现有 QA 基础夹具入口，执行前必须审查目标库门禁。
- `scripts/qa/Import-DailyLossItemConfig.ps1`：报损品类配置导入，限定 LOCAL/STAGING。
- `scripts/qa/Inspect-MySqlFlywayHistory.ps1`：只读 Flyway 历史诊断；当前只接受本地 3307，因此不能作为阶段 2 QA 空库迁移执行器。
- `scripts/qa/Verify-DailyLossConcurrencyMySql.ps1`：当前具备变量与库名门禁，但测试主体仍是 H2；阶段 2 前需改为真实 QA MySQL 集成测试。
- `scripts/qa/Verify-FrontendRoleMenus.ps1`、`frontend-vue/scripts/verify-role-menus.mjs`：角色菜单浏览器基线。
- `frontend-vue/scripts/verify-daily-loss-layout.mjs`、`verify-daily-loss-reimbursement.mjs`：日报损/报销 UI 基线；后者含 Mock API，不可替代真实权限会话。

## 阶段 2 前需确认的输入

1. 独立 MySQL QA/Test 数据库的五个环境变量，由运行环境注入，不写入文件。
2. 明确 QA 端口不得使用 3307 历史库；数据库名称必须包含 `qa` 或 `test`。
3. QA 基础数据初始化是否允许写入指定 QA 库，以及执行后的保留/清理责任人。
4. 外部平台使用 Mock 还是专用测试凭证；若使用凭证，提供注入方式和允许的调用范围。
5. 七类 QA 账号的获取方式：临时凭证注入、一次性初始化或现有账号；不得在文档存放口令。

## 建议新增 QA 脚本（阶段 2 后，经确认实施）

| 脚本 | 目的 | 安全门禁 |
| --- | --- | --- |
| `Assert-QA45-Database.ps1` | 验证变量、库名、空库和 Flyway V68 | 缺变量失败；库名不含 `qa/test` 失败；拒绝 3307 |
| `Initialize-QA45-Fixtures.ps1` | 幂等创建 `QA-45-BASE` | 默认 dry-run；需 `-Apply`；只允许 QA/Test 库 |
| `Verify-QA45-Api.ps1` | 401/403、数据范围、日志和数据库断言 | 只使用 QA 账号；默认只读，写路径需要 `-WriteVerification` |
| `Verify-QA45-Browser.mjs` | 45 项浏览器证据和截图 | 输出仅至 `output/playwright`，不提交 Git |

阶段 1 不新增上述脚本，以避免在未确认的数据库上创建测试数据。
