# AI Profit OS 真机验收矩阵

## 当前状态

批次 `20260715-rc-remediation-01`：**全部 NOT_RUN，禁止把本表视为通过。** 原因是尚无冻结候选、真实 HTTPS QA URL、E2E_ QA 账号、已构建的四端资源或授权的设备验收窗口。

测试不得使用生产 API、3307 或生产数据；业务记录不得持久化在 localStorage，登录会话仅可按 `session.ts` 的既有方式保存。

## 平台矩阵

| 端 | 最低验收范围 | 当前结果 | 证据位置 |
|---|---|---|---|
| H5 桌面浏览器 | 登录、刷新、退出、401/403、全角色工作台与闭环 | NOT_RUN | `output/release-evidence/<batch>/kane/` |
| Android Chrome | 登录、上传、弱网恢复、软键盘、安全区、重复提交 | NOT_RUN | `output/release-evidence/<batch>/devices/android-chrome/` |
| iPhone Safari | 登录、上传、弱网恢复、软键盘、安全区、重复提交 | NOT_RUN | `output/release-evidence/<batch>/devices/iphone-safari/` |
| Android 微信小程序 | 微信更新、相机/相册、上传、401/403、弱网 | NOT_RUN | `output/release-evidence/<batch>/devices/android-wechat/` |
| iPhone 微信小程序 | 微信更新、相机/相册、上传、401/403、弱网 | NOT_RUN | `output/release-evidence/<batch>/devices/iphone-wechat/` |
| Android App 资源包 | 相机/相册、上传、视频、权限拒绝、升级检查 | NOT_RUN | `output/release-evidence/<batch>/devices/android-app/` |
| iOS App 资源包 | 相机/相册、上传、视频、权限拒绝、安全区 | NOT_RUN | `output/release-evidence/<batch>/devices/ios-app/` |

Android/iOS 资源包在未完成签名时必须标为“未签名资源”，不得称为 APK、AAB 或 IPA 正式发布包。

## 每端必测用例

| 模块 | 通过条件 | 失败证据要求 |
|---|---|---|
| 认证 | 未登录 401 自动退出；错误角色/跨店 403；不会循环跳转 | HTTP 状态、请求 ID、角色、页面截图、脱敏日志位置 |
| 店长仓库闭环 | 只能对授权门店叫货、收货；重复点击不产生重复单 | 单据 ID、库存前后值、操作日志、复现步骤 |
| 巡检整改 | 店长待办、拍照上传、提交；运营队列、通过/驳回/备注；历史得分未改变 | 巡检 ID、附件 ID、状态转移、日志和截图 |
| 员工模块 | 视频 Range、考试交卷、员工助手正常/不可用降级 | HTTP 状态、Range 头、提示截图、请求 ID |
| 财务/老板 | 只读摘要权限正确；无跨店、跨仓或跨员工泄露 | 用户角色、目标范围、403 证据 |
| 附件/PDF | 上传、微信图片补传关联、下载/预览、PDF 打印可用 | 附件/PDF ID、Content-Range/Content-Type、操作日志 |
| AI/YOLO | 正常结果可见；不可用时业务化降级；模型建议不自动扣分 | 响应状态、降级文案、巡检得分前后对比 |
| 网络与交互 | 断网恢复、弱网超时、重复提交、软键盘和安全区 | 网络条件、时间、请求 ID、截图/录屏、复现步骤 |

## 记录格式

每一项实际运行后追加一条 JSON 或 Markdown 记录到对应证据目录，至少含：批次、候选 SHA-256、版本、设备型号/OS/浏览器或容器版本、账号角色、门店范围、步骤、预期、实际、状态（PASS/FAIL/BLOCKED/WAIVED）、HTTP 状态、请求 ID、日志位置、截图路径和清理结果。日志不得包含密码、令牌、密钥、完整聊天内容或生产数据。
