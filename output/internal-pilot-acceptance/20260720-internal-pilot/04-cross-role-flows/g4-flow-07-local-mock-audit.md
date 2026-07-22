# G4 FLOW-07 内部受控 YOLO 最小放行记录

- 执行时间：2026-07-22（Asia/Shanghai）。
- 范围：仅 FLOW-07 内部试用最小门禁；未进入 G5。
- 环境：既有 Docker QA MySQL 仅绑定 `127.0.0.1:13307`；`qa` profile 候选后端仅绑定 `127.0.0.1:18181`；一次性 YOLO Mock 仅绑定 `127.0.0.1:19090`。
- 数据：仅使用既有 `e2e_g4_*` 合成账号、合成门店和仓库内应用图标作为合成图片；未访问公网、局域网、第三方 YOLO、生产或真实业务数据。
- 产品决定：系统仅供公司内部人员使用，不建设图片内容审核/敏感内容安全网关。该决定不豁免身份权限、租户/门店隔离、文件真实性校验、固定服务端出站地址、脱敏审计和失败零业务写入。
- 结论：**FLOW-07 内部试用最小门禁 PASS**。这不是完整 FLOW-07/G4 PASS；延期项仍在，G4 保持 BLOCKED，G5 保持 PENDING。

## 服务端安全边界

1. `/api/inspections/detect` 的 `storeId` 为服务端必填参数。认证、`inspection.manage`、INSPECTION 门店范围及认证租户内门店存在性均先于文件字节读取和 YOLO 调用。
2. YOLO 地址只来自服务端配置；请求不能提交目标地址。QA 默认 `outbound-mode=DISABLED`，验收时仅显式启用 `MOCK`，巡检识别进一步只允许字面量 `127.0.0.1`（拒绝 `localhost`、`::1` 和其他主机）。
3. JDK HTTP 客户端使用 `Redirect.NEVER`，本地 Mock 不能通过 3xx 把请求转到其他主机。
4. 图片上限为 10 MiB；仅允许 JPG/JPEG/PNG；扩展名、声明 MIME 与 ImageIO 实际格式必须一致；先读取宽高并限制单边 8192、总像素 2000 万，再完成真实解码。损坏或伪造图片拒绝。
5. YOLO 响应必须包含数组型 `detections`；超时、5xx 或非法响应返回中文可恢复错误。识别接口本身不创建/更新巡检记录，也不自动扣分。
6. 普通日志未记录原图、文件名、图片内容或识别结果。操作审计只写租户/用户、固定动作、门店、成功或失败、耗时及白名单错误码；`target_id`、`before_json`、`after_json` 均为空。

## 真实候选 HTTP / Mock / QA 数据证据

| 核心项 | 状态 | 真实结果 |
| --- | --- | --- |
| 正常识别 | PASS | 督导登录后健康检查 `200/UP`；合成 PNG 调用 `/api/inspections/detect` 返回 `200`、1 条建议；正常 Mock `/detect` 恰好 1 次。 |
| 401 | PASS | 匿名请求返回 `401/UNAUTHORIZED`；Mock 调用数未增加。 |
| 403 | PASS | EMPLOYEE 直接调用返回 `403/FORBIDDEN`；Mock 调用数未增加。 |
| 跨门店 | PASS | 督导请求未授权门店返回 `403/FORBIDDEN`；Mock 调用数未增加。 |
| 跨租户/未知租户门店 | PASS | 督导提交不属于认证租户的门店 ID 返回通用 `403/FORBIDDEN`；Mock 调用数未增加。 |
| 非图片 | PASS | `text/plain + .txt` 返回 `400/INSPECTION_IMAGE_TYPE_NOT_ALLOWED`；Mock 调用数未增加。 |
| 超限图片 | PASS | 10 MiB + 1 byte 返回 `413/INSPECTION_IMAGE_TOO_LARGE`；Mock 调用数未增加。 |
| YOLO 超时 | PASS | 500 ms 候选超时 + 1800 ms 回环 Mock 返回 `502/INSPECTION_SERVICE_UNAVAILABLE`；超时 Mock 恰好 1 次。 |
| YOLO 5xx | PASS | 回环 Mock 返回 503，候选返回 `502/INSPECTION_SERVICE_UNAVAILABLE`；5xx Mock 恰好 1 次。 |
| 失败零业务写入 | PASS | 验收前后租户 1 的 `inspection_record` 均为 1 条；本轮时间窗口内 `updated_at` 变化 0 条。未创建识别结果、未自动扣分。 |
| 脱敏审计 | PASS | `inspection_detection_request` 共 5 条：成功 1、失败 4；文件名、检测标签、图片 MIME/内容及 before/after/target 泄漏检查 0 命中。权限拒绝继续走既有 `permission_denied` 审计。 |

## 自动化、构建与桌面主流程

- 后端定向：37/37，通过；包含 loopback 正常/无结果/超时/5xx/非法响应、禁止重定向、QA 拒绝主机别名、文件校验、跨范围先拒绝、H2 脱敏审计、人工巡检录入及整改复核。
- 后端全量：182 套件、879/879，通过；0 failure、0 error、0 skipped。
- Maven package：通过；在全量测试成功后使用 `-DskipTests` 仅生成候选包，避免重复执行同一全量测试。
- 前端：`vue-tsc -b` 通过；Vite production build 通过。
- Chromium：1280×720 的“上传 → 识别建议 → 人工确认”主流程 1/1 通过。该用例使用浏览器 API 拦截验证页面交互，只作为 UI 回归，不替代上表的真实候选 HTTP/Mock/QA 数据证据。

## 延期项与门禁边界

以下项目本轮未执行，不得写为 PASS：

1. 真实候选的“无结果”、非法 JSON/非法结构和 3xx 重定向演练（已有定向自动化，尚无本轮真实候选 HTTP 证据）。
2. 真实 QA 持久化巡检记录上的确认、撤销和人工调整完整闭环；本轮仅做 1280px UI 主流程和既有 H2 人工流程回归。
3. 经营助手、员工助手正常/未配置/超时/人工接管的本轮重新执行；沿用既有历史证据，不计入本轮最小门禁。
4. 完整 FLOW-07 五态复验、移动端、性能/并发/恢复及 G5～G7。

因此仅允许记录“FLOW-07 内部试用最小门禁 PASS”；**G4 完整门禁继续 BLOCKED，不得进入 G5**。

## 清理

- 一次性候选后端、Vite 和三种 Mock 均已停止；`18174`、`18181`、`19090` 无监听。
- 专用 QA MySQL 容器已停止；`13307` 无监听。未清库、未恢复基线、未删除审计或会话元数据。
- 失败 Compose 尝试产生的未启动临时容器壳已删除；既有 QA 数据卷和正式 QA 容器未删除、未重建。
