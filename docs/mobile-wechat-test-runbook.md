# 微信小程序一期测试运行手册

## 构建门禁

1. 使用 Node 20 LTS 与 `mobile-uniapp/package-lock.json` 执行 `npm ci`、`npm run type-check`、`npm run build:mp-weixin`。
2. 候选构建必须从干净、已提交源码运行 `scripts/build-mobile-candidates.ps1 -Target mp-weixin`，并只由受控环境提供 `MOBILE_STAGING_MP_WEIXIN_APP_ID`、`MOBILE_STAGING_MP_WEIXIN_API_BASE_URL`。
3. 检查输出不含 `mobile-api.invalid`、`localhost`、局域网地址、主 Token 或密钥；记录 SHA-256。

## QA API 验收

在隔离 QA MySQL（绝不使用 3307）中为店长、仓库、运营/督导、员工、财务、老板各准备一个授权账号：

- 未登录调用、错误角色、跨店库存/巡检/整改、跨店附件读取必须分别得到 401/403 且审计记录存在。
- 同一 `clientRequestId` 重复叫货返回原单；同一收货、考试交卷、巡检稳定 ID 重试均不得重复写入。
- 验证叫货 → 发货 → 收货的库存和日志；验证整改上传 → 提交 → 驳回/通过的附件、历史分数与日志。
- 验证视频票据不含主 Bearer、Range/206、票据到期重取一次；验证 AI/YOLO 不可用时可人工完成巡检。

## 微信开发者工具与真机

- 以合法 HTTPS 域名和 AppID 配置项目，测试登录、四 Tab、返回导航、权限失效。
- 分别测试拍照、相册、断网后重试、附件上传、视频播放/拖动/续播、考试交卷、整改复核。
- 覆盖 375px 和 390px 视图，确认 44px 点击目标、安全区、中文错误提示和禁用态。

将构建日志、截图、接口结果和 SHA-256 放入 `output/mobile-wechat-evidence/`。缺少 QA HTTPS 域名、合法 AppID、微信合法域名或真机结果时，必须保持 **不可上传体验版**。
