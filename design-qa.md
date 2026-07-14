# 历史 QA：利润表旧版视觉迁移

**Findings**
- No P0/P1/P2 issues remain for the `/profit-table` old-HTML visual parity pass.

**Evidence**
- Source visual truth path: `output/ui-parity/old-profit-table.png`
- Implementation screenshot path: `output/ui-parity/vue-profit-table-after.png`
- Full-view comparison evidence: `output/ui-parity/profit-table-comparison.png`
- Additional implementation state: `output/ui-parity/vue-profit-table-all-after.png`
- Viewport: 1920x1080 desktop
- State: boss logged in, `/profit-table?mode=single&storeId=rx13`, 2026-07

**Required Fidelity Surfaces**
- Fonts and typography: Vue3 follows the old HTML business-report hierarchy with compact controls, report title, statement rows, totals, and tabular numeric alignment.
- Spacing and layout rhythm: the heavy outer page shell was removed; filter and statement sections use the old compact rhythm.
- Colors and visual tokens: active state uses orange emphasis; totals and positive net profit use green; muted rows use subdued gray.
- Image quality and asset fidelity: this screen has no product imagery beyond existing UI icons.
- Copy and content: the page keeps `利润表`、`单店利润表`、`全部门店汇总`、`营业总收入`、`实收收入`、`成本`、`费用`、`净利润`.

**Patches Made**
- Rebuilt `ProfitTablePage.vue` as a lightweight report page.
- Added the old-style segmented mode switch, filter card, single-store statement table, and all-store summary table.
- Added Playwright regression coverage for the report structure.

**Open Questions**
- Old and Vue3 screenshots can show different stores or amounts because Vue3 remains API-backed and route-driven.

---

# Design QA：全局 Vue3 重构

## 对照范围

- 设计稿：`C:/Users/34706/Documents/Codex/2026-07-10/new-chat/outputs/product-design/ai-profit-os-ui-final.png`
- 实现截图：`output/playwright/boss-dashboard-final.png`
- 登录截图：`output/playwright/login-final.png`
- 并排对照：`C:/Users/34706/Documents/Codex/2026-07-10/new-chat/outputs/product-design/ai-profit-os-ui-comparison.png`
- 桌面视口：1487×1058；侧栏自动化另覆盖 1920×1022、1920×1080、1366×768。

## 视觉检查

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| 应用框架 | 通过 | 240px 白色侧栏、浅灰主背景、固定用户区、单一内容滚动 |
| 信息层级 | 通过 | 页面标题、工具栏、KPI、主工作区、明细页签顺序与设计稿一致 |
| 色彩 | 通过 | 橙色主操作、绿色正常、红色风险、深色正文、浅灰分隔 |
| 圆角与阴影 | 通过 | 业务容器圆角不超过 8px，移除渐变和重阴影 |
| 老板工作台 | 通过 | KPI 分段、紧急待办、七月趋势、明细页签、真实附件缩略图能力 |
| 登录页 | 通过 | 单一白色登录面板、中文校验、无渐变和技术提示 |
| 溢出与滚动 | 通过 | 1487×1058 下 body/html 横纵溢出均为 0；内容区独立滚动 |
| 控制台 | 通过 | 最终老板工作台截图时 0 error、0 warning |
| 空数据与错误 | 通过 | 空数据为短中文状态；API 技术错误在页面统一清洗 |

## 缺陷闭环

- P1：顶部工具栏受旧网格列样式影响，未铺满主内容。已重置为单列全宽，左右边距均为 56px。
- P1：后端缺少可选数组时老板页可能出现组件更新异常。已增加 dashboard、月份和待办数组防护。
- P1：注销时在途受保护请求仍可能完成。已增加跨模块会话阻断标记，相关 E2E 已通过。
- P2：favicon 404 造成控制台错误。已使用空数据图标声明消除请求。
- P2：登录页显示“检查 8080 服务”。已改为业务中文提示。

## 自动化证据

- `npm run build`：通过。
- `tests/e2e/08-sidebar-logout.spec.ts`：14/14 通过。
- `tests/e2e/09-sidebar-routing.spec.ts`：3/3 通过。
- `mvn -q test`：通过；Flyway 测试迁移到 v31。

## 非阻塞项

- Vite 仍提示主 JS 包大于 500 kB。属于 P3 性能优化，建议后续按路由拆包，不影响本轮功能和视觉验收。
- 本轮按要求不开展多分辨率页面重排；仅保留现有移动侧栏回归测试。

## 登录页安全重构 QA

- 设计稿：`C:/Users/34706/Documents/Codex/2026-07-10/new-chat/outputs/product-design/login-ui-final.png`
- 默认态：`output/playwright/login-security-final.png`
- 字段错误态：`output/playwright/login-security-error-final.png`
- 对照图：`C:/Users/34706/Documents/Codex/2026-07-10/new-chat/outputs/product-design/login-ui-comparison.png`
- 视口：1487×1058。
- 页面使用全宽品牌栏和横向安全登录工作区，无小卡片、渐变和双重滚动。
- 账号和密码默认均为空；密码可见性、字段级错误、记住账号、登录帮助和提交状态完整。
- 记住账号仅写入 `ai_profit_remembered_username`，不保存密码。
- 健康状态只在真实 `/api/health` 返回 `UP/OK/HEALTHY` 时显示；当前旧运行实例返回 500，因此页面不显示虚假正常状态。
- 视觉测量：body 横纵溢出均为 0，品牌栏宽度等于 1487px 视口宽度。
- 登录 E2E：4/4 通过；覆盖 401、429、回车、重复提交、目标页返回和账号记忆。

final result: passed
