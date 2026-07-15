# UniApp 三端移动端一期发布清单

状态：候选版，禁止直接发布  
移动端版本：`0.1.0`  
App `versionCode`：`100`  
适用端：H5、微信小程序、Android App、iOS App

## 1. 本期发布边界

- `frontend-vue` 继续作为桌面正式前端；`mobile-uniapp` 是独立手机端，不替换桌面入口。
- 业务数据只来自 Spring Boot API 和 MySQL。端侧持久化只允许保存登录 Token，不保存库存、叫货、巡检、附件、考试、成绩、财务或助手业务记录。
- 财务和老板只提供只读摘要。经营录入、月度导入、账号权限、复杂仓库配置、采购配置和批量导出继续使用桌面端。
- 本清单只生成候选资源和验证证据，不操作生产数据库，不提交小程序审核，不上传应用商店，不发布生产环境。

## 2. 版本与候选产物

| 目标 | npm 命令 | CLI 原始目录 | 候选类型 | 本期是否可直接发布 |
| --- | --- | --- | --- | --- |
| H5 | `npm run build:h5` | `mobile-uniapp/dist/candidate/h5` | 静态网页资源 | 否，需正式域名、HTTPS、反向代理和验收 |
| 微信小程序 | `npm run build:mp-weixin` | `mobile-uniapp/dist/candidate/mp-weixin` | 小程序工程源码 | 否，需 AppID、合法域名、隐私声明和微信审核 |
| Android | `npm run build:app:android` | `mobile-uniapp/dist/candidate/android` | 未签名 App 资源 | 否，需 HBuilderX/原生工程、包名和签名证书生成 APK/AAB |
| iOS | `npm run build:app:ios` | `mobile-uniapp/dist/candidate/ios` | 未签名 App 资源 | 否，需 macOS/Xcode、Bundle ID、证书和描述文件生成 IPA |

统一构建和 SHA-256 命令：

```powershell
npm --prefix mobile-uniapp ci
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-mobile-candidates.ps1 `
  -Target all `
  -ApiBaseUrl 'https://<移动端可访问的候选后端域名>'
```

脚本输出到 `output/mobile-release-evidence/`：

- `artifacts/*.zip`：四端候选压缩包。
- `artifacts/*.manifest.json`：版本、构建环境、Git 提交、产物类型和 SHA-256。
- `artifacts/SHA256SUMS.txt`：发布核验清单。
- `build-logs/*.log`：逐端构建日志。
- `candidate-build-summary.json`：候选构建汇总。

H5 可以使用同源 `/api` 构建；微信小程序和 App 构建必须明确传入可从真机访问的 HTTPS API 地址。禁止将数据库密码、AI Key、上传密钥、推送证书或签名密码放入 `VITE_*` 构建变量。

## 3. 通用发布前门禁

> **当前生产阻断：** 2026-07-15 使用 npm 官方源执行审计：运行时依赖共 33 项风险，其中 9 项高危、0 项严重；完整依赖共 44 项风险，其中 11 项高危、0 项严重。风险沿 DCloud UniApp 工具链传递。`npm audit fix --force` 会把关键 DCloud 包破坏性降级，不得使用。必须升级到 DCloud 发布的兼容修复版本并重新通过四端构建与审计后，才能解除生产阻断。

> **构建环境门禁：** 当前证据由 Node `24.12.0` 生成，四端编译虽然成功，但 UniApp 官方 Vue3/Vite CLI 文档当前列出 Node 18/20。正式候选必须在 Node 20 LTS 使用 lockfile 全新安装并重建，不能直接把当前 ZIP 当作可发布包。

- [ ] 使用 Node 20 LTS 安装依赖，执行 `npm ci`，没有忽略 lockfile；当前 Node 24 构建仅作为技术候选。
- [ ] `npm audit --omit=dev --registry=https://registry.npmjs.org` 无未处置高危项；当前 9 项高危未清零，禁止生产发布。
- [ ] `npm run type-check` 通过。
- [ ] H5、MP-WEIXIN、Android 资源、iOS 资源四个构建命令全部通过。
- [ ] `mvn -q test` 与 `mvn -q -DskipTests package` 通过。
- [ ] 在独立空 MySQL 8 数据库执行全部 Flyway 迁移；不得连接生产库或复用生产账号。
- [ ] `GET /api/auth/me` 未登录返回 401；Token 过期或权限版本变化也返回 401。
- [ ] 跨店、跨仓、附件越权返回 403，并能在操作日志中定位拒绝记录。
- [ ] 叫货重复提交、收货重复确认、考试重复交卷等幂等测试通过。
- [ ] 移动端代码静态扫描确认只有会话 Token 使用 `uniStorage`，没有业务数据持久化。
- [ ] 仓库和巡检页面展示的数据均可追溯到真实 API 响应，不包含演示回退数据。
- [ ] 所有主要按钮实际触控高度不小于 44px；列表以卡片摘要呈现，无整页横向表格。
- [ ] 固定提交栏适配底部安全区和软键盘，输入时不会遮挡主要提交按钮。
- [ ] 日志、错误上报、构建产物和源码中没有 Authorization、密码、AI Key、签名材料或推送证书。

## 4. 三端平台检查

### H5

- [ ] 正式域名启用 HTTPS、HSTS、CSP 和正确的缓存策略。
- [ ] 后端 CORS 仅允许明确的 H5 域名，不使用任意源。
- [ ] Android Chrome 和 iPhone Safari 均验证浏览器相机/相册选择；不支持直接相机时能降级为文件选择。
- [ ] 浏览器刷新后只恢复 Token，再从 `/api/auth/me` 和业务 API 重取权限与数据。
- [ ] 反向代理支持受保护视频的 `Range`/`206 Partial Content`。
- [ ] 在 H5、微信、Android App、iOS App 的真实网络请求中确认播放器会发送 `Range`，拖动进度不会触发整段下载；无 `Range` 时最多 20MB 的兼容响应不得被当作弱网验收通过。
- [ ] H5 播放路径只含短时视频票据，不含主 Bearer；票据过期后仅自动重签一次。
- [ ] 网关、CDN、反向代理和访问日志不记录完整 `ticket` 查询参数，或已验证固定脱敏规则。

### 微信小程序

- [ ] 在微信公众平台配置 `request`、`uploadFile`、`downloadFile` 合法 HTTPS 域名。
- [ ] 已配置正式小程序 AppID；候选工程中没有私密 AppSecret。
- [ ] 相机、相册、上传、受保护视频票据流和用户拒绝授权的中文提示均在 Android/iPhone 微信真机验证；播放器不再整段 `downloadFile` 后播放。
- [ ] `uni.getUpdateManager()` 的发现更新、下载完成、应用更新和失败提示均验证。
- [ ] 登录先使用现有账号体系；微信账号绑定没有被默认开启，也没有绕过现有权限。
- [ ] 完成微信隐私保护指引、用户信息处理说明、类目与服务内容核对后才能提交审核。

### Android App

- [ ] `manifest.json` 的 DCloud AppID、Android 包名、应用名、版本 `0.1.0`、版本号 `100` 已由正式账号确认。
- [ ] `manifest.json` 已包含 `Camera` 和 `VideoPlayer` App 模块；正式 HBuilderX/原生基座重新打包后验证模块实际生效。
- [ ] 相机、相册、上传、网络拒绝、权限拒绝和版本检查在目标 Android 真机验证。
- [ ] 使用正式 keystore 在受控构建机签名；别名、密码和证书文件不进入仓库或构建日志。
- [ ] 记录 APK/AAB 的 SHA-256、签名证书 SHA-256、包名、minSdk/targetSdk 和构建时间。
- [ ] App 更新下载地址使用受控 HTTPS 域名；客户端不静默安装更新。
- [ ] 推送适配接口保持关闭，取得厂商/个推账号、证书和运营同意后单独开启。

### iOS App

- [ ] 在 macOS/Xcode/HBuilderX 环境配置正式 Bundle ID、Apple Team、Distribution Certificate 和 Provisioning Profile。
- [ ] `manifest.json` 的 `VideoPlayer` 模块进入正式 iOS 基座，并验证带 Authorization Header 的 Range 播放。
- [ ] `Info.plist` 的相机、相册用途说明为中文且与实际功能一致。
- [ ] 相机、相册、上传、安全区、键盘、版本检查在目标 iPhone 真机验证。
- [ ] 记录 IPA 的 SHA-256、Bundle ID、Team ID、版本 `0.1.0`、Build `100` 和构建时间。
- [ ] App Store Connect 隐私问卷、数据收集说明、出口合规和截图资料完成后才能提交。
- [ ] APNs Key/证书在推送策略确认前不进入工程；一期推送保持关闭。

## 5. 真实业务验收矩阵

每个设备都必须覆盖：登录、按权限生成菜单、弱网、401 会话过期、403 权限拒绝、拍照、相册、上传、重复提交、退出登录。

| 设备/容器 | 登录与菜单 | 弱网/会话 | 拍照上传 | 重复提交 | 当前状态 |
| --- | --- | --- | --- | --- | --- |
| Android Chrome | 待验收 | 待验收 | 待验收 | 待验收 | 未执行 |
| iPhone Safari | 待验收 | 待验收 | 待验收 | 待验收 | 未执行 |
| Android 微信 | 待验收 | 待验收 | 待验收 | 待验收 | 未执行 |
| iPhone 微信 | 待验收 | 待验收 | 待验收 | 待验收 | 未执行 |
| Android App | 待验收 | 待验收 | 待验收 | 待验收 | 未执行 |
| iOS App | 待验收 | 待验收 | 待验收 | 待验收 | 未执行 |

Kane CLI 必须先完成登录，再用 `kane-cli generate ... --agent` 生成验收场景并保存；不得用手写用例冒充 Kane 产物。H5 浏览器执行结果、截图和 Kane NDJSON 放入 `output/mobile-release-evidence/kane/`。微信和 App 真机结果按设备记录系统版本、微信/App 版本、执行人、时间、结论和截图哈希。

当前受保护视频票据保存在单个 Spring Boot 实例内。若发布为多实例，必须先完成粘性会话或共享 Redis 票据存储（带 TTL、视频/会话范围绑定和每段 auth_token 回查），并新增跨实例 Range 验收；否则禁止无粘性负载均衡发布。

## 6. 角色闭环验收

- [ ] 店长：查看本店库存；编辑多物料数量；提交叫货；看到原叫货单；仓库发货后确认收货；重复收货不重复入库。
- [ ] 督导：查看范围内任务；拍照/相册；原图上传；查看 AI 建议；人工确认；把附件明确关联到巡检条款；保存后查看整改状态。
- [ ] 员工：播放受保护培训视频；上报学习进度；打开本人试卷；答题交卷；重复交卷返回原成绩；查看成绩；使用员工服务助手。
- [ ] 财务：只读查看权限范围内经营摘要，找不到经营录入、月度导入和批量导出入口。
- [ ] 老板：只读查看全公司摘要和考试摘要，复杂权限和配置入口明确引导桌面端。

## 7. 发布前仍需的账号与资料

- 移动端可访问的候选/生产 HTTPS API 域名、TLS 证书、反向代理配置和后端 CORS 白名单。
- 具备每个角色和数据范围的脱敏验收账号：店长、督导/运营、员工、财务、老板，以及跨店/跨仓拒绝账号。
- 微信公众平台主体、小程序 AppID、开发者权限、合法域名、类目、隐私保护指引、图标、截图和审核文案。
- DCloud 开发者账号和 AppID；Android 正式包名、keystore、别名、签名密码、应用市场账号、隐私政策和上架素材。
- Apple Developer Team、Bundle ID、Distribution Certificate、Provisioning Profile、App Store Connect 权限、隐私问卷、用途说明、截图和审核备注。
- 如后续启用推送：APNs Key/证书、Android 厂商推送/个推凭据、隐私授权文案、消息分类、发送策略和运营审批。

## 8. 停止条件

满足代码、契约测试、四端资源构建和可执行环境内的验证后即停止。没有上述账号、证书、真机和审核资料时，不生成伪造签名包，不提交审核，不上传商店，也不将候选 API 地址改成生产地址。
