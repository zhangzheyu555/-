# AI-01～AI-02 智能助手隔离验收报告

- 执行时间：2026-07-21（Asia/Shanghai）
- 范围：仅桌面 Web；AI-01 门店经营助手、AI-02 员工服务助手。
- 结论：**PASS（仅桌面隔离复验；移动端与真实 QA 登录会话按当前范围未执行）**。

## 隔离与安全边界

- 已阅读项目 `AGENTS.md`、45 项验收矩阵、助手/员工助手实现及现有自动化。
- 浏览器仅访问本机 Vite：`127.0.0.1:18174`；所有 `/api/assistant/**`、`/api/employee-assistant/**` 与认证请求均由 Playwright route Mock 响应。
- 未启动后端服务，未连接 QA MySQL、Docker、局域网或生产；未使用真实模型、凭证、token 或外网地址。
- 员工助手定向后端测试中的上游场景为进程内 loopback Mock；因首个桌面 Chromium 门禁失败，本轮没有启动这些后端测试。

## 阻断证据

执行的桌面 Chromium 命令（仅 `--project=chromium`，过滤 `mobile|390px`）计划运行 36 项助手回归。第一个文件 `20-employee-assistant-status.spec.ts` 出现 4 个失败后，按“任一失败即停止”中断：

| 用例 | 期望 | 当前页面实际值 | 结果 |
| --- | --- | --- | --- |
| `UNCONFIGURED` 状态 | `服务未配置` | `未配置` | FAIL |
| `AUTH_FAILED` 状态 | `服务授权异常` | `授权异常` | FAIL |
| `UNAVAILABLE` 状态 | `服务暂不可用` | `暂不可用` | FAIL |
| `READY` 状态 | `服务已就绪` | `已就绪` | FAIL |
| 就绪状态发问 | 按钮名 `发送问题` | 当前按钮不含该名称（页面使用 `发送`） | INTERRUPTED |

- Playwright 汇总：**4 failed、1 interrupted、31 did not run**；没有将失败后的项目计入通过。
- 失败位置在 [20-employee-assistant-status.spec.ts](/Users/a1/Documents/Codex/2026-07-19/zhe/work/AI-Profit-OS-Light-Integrated-20260719/src/frontend-vue/tests/e2e/20-employee-assistant-status.spec.ts:50) 和 [EmployeeAssistantPage.vue](/Users/a1/Documents/Codex/2026-07-19/zhe/work/AI-Profit-OS-Light-Integrated-20260719/src/frontend-vue/src/pages/EmployeeAssistantPage.vue)。现有回归断言与当前页面的短状态徽标/按钮文案不一致，尚未确认应以哪一个作为正式文案契约。

## 未执行

- AI-01/AI-02 其余桌面 Chromium 回归、1280px 布局、授权角色与 403、匿名 401、跨店/跨员工上下文隔离、模型不可用降级、会话/反馈/转交幂等及审计：**NOT RUN**，避免在硬门禁失败后继续扩大测试范围。
- 定向 H2/loopback Mock 后端测试：**NOT RUN**；全量、打包、类型检查和生产构建不在本子验收任务范围内。

## 清理

- 本次 Vite 预览已停止；`18174` 无监听。
- Playwright 浏览器上下文已由中断命令退出；未创建业务数据、持久化会话、权限覆盖或审计记录。
- 未修改业务代码、`g3-gate-ledger.md` 或其他工作区既有变更，未提交代码。

## 最小解锁条件

1. 确认员工助手状态徽标和发送按钮的正式中文文案：要么将页面恢复为现有测试要求的完整业务文案，要么只更新直接关联的桌面回归断言为当前短文案，并在状态说明区域断言完整中文降级指引；不得只删除断言。
2. 重新执行完整的 36 项桌面 Chromium 助手集（继续排除所有移动端/390px 用例）。
3. 在 Chromium 全部通过后，执行 AI-01/AI-02 定向 H2/loopback Mock：授权/403、匿名 401、跨店/跨员工上下文隔离、禁用模型中文降级、会话/反馈/转交幂等与审计；再更新本报告。

## 2026-07-21 最小文案修复与复验

- 修复范围仅限 `EmployeeAssistantPage.vue` 的桌面业务文案与关联回归：四种状态统一显示为“服务未配置 / 服务授权异常 / 服务暂不可用 / 服务已就绪”，并在同一状态区域展示对应下一步；操作按钮统一为“检查服务”和“发送问题”。
- 已发布本地知识库可用而上游未配置时，状态明确为“标准话术可用”，不显示上游响应中的地址、token 或其他配置详情；部署变量说明仅对 BOSS 显示，且仅含变量名、无变量值。
- 与该按钮文字直接关联的 `31-employee-assistant-experience.spec.ts` 已同步为“发送问题”；未变更权限、服务状态判定、出站、降级或业务流程。

### 已通过的定向桌面证据

- 命令：`E2E_BASE_URL=http://127.0.0.1:18174 playwright test tests/e2e/20-employee-assistant-status.spec.ts --project=chromium --grep-invert 'mobile|390px'`
- 结果：**9/9 通过**。覆盖四种服务状态、就绪会话仅提交 `message/sessionId`、授权失败/超时禁用后续提问、普通用户无部署变量、已发布本地知识库的安全降级。

### 本轮再次阻断的证据

- 随后启动 36 项桌面 Chromium 助手集（七个助手 spec，明确排除 `mobile|390px`）。第 14、15 项在经营助手既有契约失败后立即中止；执行汇总为 **15 passed、2 failed、1 interrupted、18 did not run**。本轮不将中断项计为通过。
- 失败一：`22-assistant-status-refresh.spec.ts` 的“AI 未配置仍可本地查数”用例没有找到“经营数据”。测试夹具未拦截页面现会请求的 `/api/finance/dashboard`，Vite 代理拒绝本地不存在的 `127.0.0.1:18081`；未访问任何外网、QA 或生产服务。
- 失败二：同文件的 `RESPONSE_REJECTED` 预期“模型格式异常”，当前页面显示“分析结果待复核”。这是经营助手的状态文案契约差异，不属于本次员工助手文案修复范围。
- 第 18 项超时边界用例因硬门禁中止，不能用于判断业务结果。后续 AI-01 的授权/403、匿名 401、跨店数据快照、提问/失败/转交审计及 AI-02 的定向 H2/loopback Mock 均未运行。

### 清理与最小解锁

- 本轮浏览器请求均由 Playwright Mock 提供，唯一未拦截的本机代理请求被拒绝；未启动后端、未连接 MySQL、Docker、局域网、QA 或生产，也未使用真实模型、凭证或 token。
- 本地 Vite `18174` 已停止，端口无监听；没有创建业务数据、持久化会话、权限覆盖或审计残留。
- 当前结论仍为 **BLOCKED**。最小解锁为：先补齐经营助手桌面测试夹具对当前所需本地经营数据请求的 Mock，并确认 `RESPONSE_REJECTED` 应采用“模型格式异常”还是“分析结果待复核”的正式业务文案；之后从 36 项桌面 Chromium 助手集重新执行，通过后才可运行定向 H2/loopback Mock 授权、隔离、幂等与审计测试。

## 2026-07-21 经营助手夹具修复后的桌面复验

- `22-assistant-status-refresh.spec.ts` 和 `25-assistant-analysis-quality-states.spec.ts` 已为当前页面实际依赖的 `/api/finance/dashboard` 增加本地 Playwright 响应；不再让此类读取落到 Vite 代理。
- `RESPONSE_REJECTED` 在没有具体拒绝码时统一显示“模型格式异常”；已有具体业务拒绝码仍保留“经营数据待补全”或“分析结果已拦截”等更准确的中文提示。`25` 的 `DATA_LIMITED_REQUIRED` 直接回归已同步验证“经营数据待补全”。
- 用户已明确不进行移动端测试。一个内部将视口设为 390px 的用例现已显式标记为 `[mobile]`，被桌面命令排除；当前桌面集合为 **35 项**，不再把该用例计入桌面证据。

### 已通过

- `22-assistant-status-refresh.spec.ts`（桌面）：**5/5**。
- `25-assistant-analysis-quality-states.spec.ts`（排除 `[mobile]`）：**5/5**。
- 最新 35 项桌面助手集在进入快照文件前累计 **27 项通过**，包括员工助手完整状态/会话/转接桌面回归、经营助手状态刷新、本地事实查询、16 秒 AI 超时边界、质量拒绝中文提示，以及员工助手桌面 1280px 布局。

### 新的硬阻断：经营快照契约未在当前页面实现

- `32-assistant-operating-snapshot.spec.ts` 的首项失败：当前 `AssistantPage.vue` 不渲染 `.snapshot-panel`，因此无法展示或断言页面快照 ID。
- 第二项失败：测试为 `/api/assistant/operating-snapshot` 配置了本地 Mock，但页面当前没有调用该接口，`snapshotCalls` 保持 `0`。页面仅在本地聊天响应中接收 `localData.operatingSnapshot`，却未对页面快照、回答快照与后续 AI 请求做可验证的一致性绑定。
- 本轮汇总：**27 passed、2 failed、1 interrupted、5 did not run**。第 30 项是因门禁中止的中断项，不计为通过；未运行定向 H2/loopback Mock、授权/403、匿名 401、跨门店/跨员工隔离、幂等或审计回归。

### 清理与最小解锁

- `18174` 已停止且无监听。夹具已覆盖的请求均为 Playwright 本地响应；在快照文件中发现的未实现调用没有作为通过证据使用。未创建业务数据、持久化会话、权限覆盖或审计残留。
- 仍未连接 QA 或生产数据库、未使用真实模型、凭证或外网服务。
- 最小解锁不应只放宽断言：恢复或实现只读的当前经营快照加载、在页面保存并显示其不敏感 ID/范围、拒绝回答中缺失或与页面不同的快照，并确保后续 AI 请求携带同一 ID；补齐“数据不足且未调用 AI”与实时 `DATA_LIMITED` 的桌面呈现后，重跑 35 项桌面套件，成功后再执行 H2/loopback Mock 后端验收。

## 2026-07-21 经营快照与审计闭环复验

### 最小实现

- 经营助手页面认证后只读加载 `/api/assistant/operating-snapshot`，只显示可审查的快照 ID、授权范围和更新时间；不显示提示词、模型原文、凭证或经营明细。
- 每次本地查询、深度分析和重试均携带页面当前快照 ID；返回快照 ID 缺失、冲突或过期时，页面拒绝展示可能混用的数据，并提示中文可恢复动作。跨租户快照被服务端拒绝为 `SNAPSHOT_EXPIRED`，不会重新构建或泄露原租户事实。
- `AssistantService` 对成功提问写 `assistant.chat`，对可预期拒绝/失败写 `assistant.chat_rejected`；`AssistantController` 对已认证但无权限的访问沿用 `permission_denied` 审计。记录仅含租户、操作者、操作类型、授权范围类别、快照是否绑定和模型调用状态；不写问题正文、prompt、原始模型回答、token、金额或其他经营明细。
- 员工助手人工转接已有 `employee_assistant.handoff_*` 审计；本轮补充反馈 `employee_assistant.feedback` 的脱敏断言。经营助手“加入待办”复用既有待办操作日志，本轮补充转交日志断言。

### 验证证据

- 经营快照 Chromium 专项：`32-assistant-operating-snapshot.spec.ts`，**8/8 通过**。覆盖快照加载/显示、同 ID 请求与回答绑定、数据不足不调用模型、实时 `DATA_LIMITED`、清空仅清页面会话、端点不可用/契约不兼容/返回冲突安全拒绝，以及月份切换取消迟到响应；1280px 无横向溢出。
- 已通过的完整桌面助手基线：七个 spec、排除 `mobile|390px`，**35/35 通过**。审计改动仅位于后端持久化层，未改变 Vue 组件、路由或 Mock 合约，因此本轮以 32 专项重跑作为最小桌面回归证明；不将移动端计入结果。
- 定向后端 AI 服务集：`Assistant*Test, DeepSeek*Test, OperatingSnapshotContractTest, EmployeeAssistant*Test`，**131/131 通过，0 failure / 0 error / 0 skipped**。其中包含员工助手 H2 Flyway 迁移、权限 401/403、快照一致性、模型禁用/超时/响应质量降级、知识库、反馈和人工转接覆盖。
- 审计专项：`AssistantAuditH2Test, AssistantSnapshotServiceTest, AssistantControllerAccessTest, EmployeeAssistantHandoffServiceTest, BusinessTodoServiceTest`，**20/20 通过，0 failure / 0 error**。进程内 H2 断言成功提问、屏蔽词拒绝和跨租户快照拒绝均写入所属租户 `operation_log`；跨租户拒绝行不保留原租户快照 ID，审计字段不含提问正文、测试经营金额或自由文本。已认证的权限拒绝、员工助手反馈/转接与经营助手待办转交也有直接断言。
- `vue-tsc -b`：通过。未执行全局后端套件、后端打包或前端生产构建，避免重复已通过的非本专项门禁。

### 隔离、清理与结论

- 所有浏览器请求均由 Playwright 本地 Mock 响应；后端测试仅使用 Mockito、进程内 loopback Mock 与内存 H2。未连接 QA MySQL、Docker、局域网、生产或外部模型；未读取或输出真实凭证、token、业务数据。
- 临时 Vite 仅绑定 `127.0.0.1:18174`，现已停止且端口无监听；H2、浏览器会话和 Mock 数据均为进程临时资源，无持久化测试数据、权限覆盖或测试审计残留。
- 本报告的 AI-01/AI-02 桌面隔离门禁结论为 **PASS**。残留非阻断风险：尚未以受控 QA 账号在真实 MySQL/真实登录会话中重演；移动端按当前要求未测。
