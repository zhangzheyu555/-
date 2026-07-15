# 当前上线就绪度（2026-07-15）

## 结论

**BLOCKED：当前不能作为“全终端正式上线版本”发布。**

本轮关闭了发布脚本、隔离 V54 演练和手机端第一阶段的代码门禁，但正式候选必须来自已审查、干净且已提交的 Git 提交。当前工作区仍有保留的未提交修改，V54 的 MySQL/H2 迁移尚未被 Git 跟踪，且源码门禁发现一个受跟踪业务备份路径。它们没有被删除、解除跟踪或改写历史。

运行中的 STAGING 服务也不是 V54 候选：`http://127.0.0.1:18081/api/health` 在 2026-07-15 11:16（+08:00）返回 UP，但数据库迁移版本为 **53**。它只作为现有服务可达性证据，不能代表本轮源码或发布候选。

## 证据总表

| 门禁 | 状态 | 证据与边界 |
|---|---|---|
| CI YAML | PASS（本地） | `vue3-ci.yml` 经 YAML 解析；已修复学习者环境变量缩进，并新增非秘密、自动执行的 `mobile-ui-regression` 作业。GitHub Actions 实际绿灯仍为 NOT RUN。 |
| 移动 CI 浏览器 | PASS（配置） | CI 同时安装 Chromium 与 WebKit；Android 使用 Chromium，iPhone/iPad 使用 WebKit，不会再因只安装 Chromium 而在项目启动前失败。 |
| 源码敏感数据门禁 | BLOCKED | 当前扫描发现：两个 V54 文件未被跟踪；`docs/门店数据备份.json` 被业务数据文件名规则拦截。门禁只输出路径/原因，不输出内容或密钥。 |
| 不可变候选冻结 | BLOCKED | `build-release-candidate.ps1` 正确拒绝脏工作区；未生成可部署候选。`output/release-evidence/release-manifest.json` 是明确标记为 BLOCKED 的记录，不是发布包清单。 |
| 后端回归 | PASS | 本轮 `mvn -q test` 通过；当前 Surefire 报告为 608 tests、0 failures、0 errors、0 skipped。 |
| 后端候选打包 | PASS（隔离构建） | 当前工作区的运行中 JAR 被占用，不能将该目录打包结果作为候选；隔离 V53/V54 副本均已独立生成可执行 JAR。 |
| Vue 类型检查与生产构建 | PASS | `frontend-vue` 的 `npm run build` 通过。 |
| 隔离 MySQL V54 | PASS | 临时 MySQL 8 仅监听 `127.0.0.1:3341`：空库迁移至 V54、独立 V53 基线升 V54、备份恢复（92 张表）均通过；未触碰 3307。证据见 `output/release-evidence/root-v54-20260715-105927/`。 |
| 发布与回滚脚本 | PASS（本地门禁/DryRun） | 非 root 部署账号、远端 SHA-256 校验、不可覆盖版本目录、`current` 原子 symlink 切换和显式回滚入口均已实现；未对远端执行部署。 |
| Android 设备项目回归 | PASS（模拟浏览器） | Android 412 配置项目下 16 项通过：登录/退出/会话失效重登、无横滚、抽屉语义/焦点、督导拍照上传/模型建议/人工确认、历史原图补传与弱网重试、考试、叫货编辑提交、确认收货卡片、员工助手未配置/隐私拒绝、桌面专用流程提示。所有 API 均为测试拦截，不等于真实手机或真实后端验收。 |
| iPhone/iPad 模拟浏览器 | BLOCKED（本机 WebKit 仿真） | WebKit 已安装，但本机 Windows 125% DPI 使 iPhone 390/iPad 768 描述符分别得到 `clientWidth` 312/615，而根布局仍为 390/768；登录页即可复现，巡检确认的无横滚断言因此失败。未放宽断言或改动页面来伪造通过。GitHub Actions 的 Ubuntu WebKit 与实体 iPhone/iPad 仍须独立验收。 |
| Kane 场景生成/执行 | BLOCKED | `kane-cli whoami` 显示未登录，故未运行 `kane-cli generate`，也未伪造 Kane 截图或业务验收。 |
| 真机与微信验收 | NOT RUN | 尚需 iPhone Safari、Android Chrome、微信内置浏览器的设备型号、系统版本、截图和闭环结果。 |

## 本轮实现

### 发布安全与一致性

- 源码门禁改为受跟踪路径、快照扩展名和内容特征三层检测；失败时只给出隔离、解除跟踪、历史净化和凭据轮换审批清单。
- MySQL/H2 的最新 Flyway 校验提升至 V54，既有迁移未被改写。
- 候选构建从干净 Git commit 的 `git archive` 开始，在独立目录构建前后端并生成带 commit、Flyway 版本、时间和 SHA-256 的正式清单。
- 发布/回滚脚本使用非 root 账号、哈希校验、版本目录、原子 symlink 和显式回滚；详细步骤见 `docs/release/immutable-candidate-deployment.md`。

### 手机端第一阶段

- 登录页在 `<=768px` 单列显示，移除桌面最小宽度；375/390/430/768 均有无横滚和触控尺寸断言。
- 门店叫货在窄屏使用物料卡片，商品、库存、数量加减、备注、删除与提交均完整可见且关键控件至少 44px。
- 待确认收货在窄屏改为卡片，显示单号、商品、发货时间和全宽“确认已收货”，不再把关键 CTA 藏在横向表格外。
- 移动抽屉具备 `dialog`、`aria-modal`、`aria-expanded`、`aria-controls`、Esc、焦点约束、焦点回归和路由关闭；菜单关闭、搜索/通知触控目标均为至少 44px。
- 巡检上传和历史补传声明 `image/*`、`capture=environment`，上传/删除/确认目标至少 44px；仍需在真机实际调用后置摄像头验收。
- 考试采用整行可点选项，上一题、下一题、交卷考虑 `100dvh` 和安全区。
- 员工服务助手在窄屏保留未配置的中文指引、隐私信息拒绝和不暴露内部错误码的提示，输入与操作按钮至少 44px。
- 会话失效会回到登录页并保留原业务路径；历史原图补传遇到弱网失败会保留已选文件并允许再次提交。
- 复杂财务录入、账号权限和批量导出在手机端明确提示“请在电脑端完成”，没有伪装为完整手机流程。

### 自动化覆盖

- Playwright 设备项目：Desktop Chrome、iPhone 390、Android 412、iPad 768。
- 新增 `test:e2e:mobile` 和 CI 的 `mobile-ui-regression`：从构建后的本地 Vue 预览运行 iPhone、Android、iPad 项目，并补充 Android 专属会话/弱网重试；共 44 项 mocked API 用例，因此无需 CI 密钥，也不会对真实业务数据写入。
- `29-mobile-store-requisition-receipt.spec.ts` 在真实设备描述符项目中覆盖：新增物料、数量加减、备注、删除再新增、提交叫货，以及确认收货弹框、`POST /api/warehouse/requisitions/{id}/receive`、成功刷新和无整页横滚。
- 历史补传、员工助手安全提示与会话重试分别由 `30-mobile-inspection-historical-upload.spec.ts`、`30-mobile-employee-assistant-safety.spec.ts`、`30-mobile-session-retry.spec.ts` 覆盖。

## 隔离 MySQL 演练说明

- 证据：`output/release-evidence/root-v54-20260715-105927/isolated-v54-mysql-20260715-105927.json`。
- 使用临时数据目录、临时非 root 验证账号和仅进程内环境变量；没有读取、写入或输出 3307 凭据、模型密钥或真实业务数据。
- 证明范围是 schema/migration/restore 演练，不是生产恢复，也不替代真实业务数据抽样和权限流复核。

## 正式放行顺序

1. 经数据与安全负责人审批后，将业务备份隔离到仓库外并解除跟踪；历史净化和可能的凭据轮换必须走独立受控流程。
2. 审查并提交 V54 的 MySQL/H2 迁移及本轮改动，使工作区完全干净。
3. 在冻结提交上通过源码门禁后运行 `scripts/build-release-candidate.ps1`，取得真正的候选目录和正式 `release-manifest.json`。
4. 推送并等待 GitHub Actions 全绿；在同一候选上重跑隔离 MySQL 演练。
5. 登录 Kane 后生成并执行业务浏览器场景；再人工完成 iPhone Safari、Android Chrome、微信内置浏览器的登录/退出、叫货/收货、拍照上传/人工确认、视频/答题/交卷、员工助手已配置/未配置/超时/敏感信息拒绝。
6. 全部 PASS 后使用 `scripts/deploy-release-candidate.ps1` 小流量切换；观察 24 小时。异常时用 `scripts/rollback-release-candidate.ps1` 回到上一个版本目录。数据库恢复另按获批备份方案执行，不能把前端回滚当成数据回滚。

## 本轮未执行

- 未操作 MySQL 3307、未发布远端、未替换现有 18081 服务、未删除数据库或业务备份。
- 未读取、打印、提交或写入真实密码、API Key 或令牌。
- 未将模拟浏览器、Mock API、静态检查或本地 viewport 回归描述为真机、微信或正式发布验收。
