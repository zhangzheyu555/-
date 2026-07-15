# UniApp 三端移动端一期 API 契约

状态：候选版  
适用端：H5、微信小程序、App（Android/iOS）  
后端：现有 Spring Boot `/api/**` 正式接口

## 1. 结论与边界

移动端一期直接复用现有 Bearer Token 认证、权限目录、数据范围、MySQL 业务表、附件服务和操作日志，不新增移动端专用登录，不新增 Cookie 依赖，也不复制桌面端接口。

现有 Token 对三端均适用：Token 是不透明随机串，通过 `Authorization: Bearer <token>` 传递，不依赖浏览器 Cookie；服务端存放于 `auth_token`，默认有效期 12 小时，并绑定账号 `permissionVersion`。Token 过期、账号停用或权限版本变化后立即返回 401。

一期不增加 Refresh Token。原因是当前 Bearer 协议已跨平台，增加长期刷新凭据会扩大泄露面并需要新的吊销、轮换和设备管理模型。客户端遇到 401 时清除本地登录会话并回到登录页。后续如需要长期免登录，应单独设计设备会话、Refresh Token 轮换、散列存储、设备注销和风险审计，不得复用业务表或把刷新凭据写入日志。

移动端只可持久化登录会话。库存、叫货单、巡检、附件、考试、成绩、财务摘要和助手会话的业务结果均以服务端 API/MySQL 为准，不得写入 `localStorage`、`uniStorage` 或其它端侧持久化作为业务数据源。

## 2. 通用协议

除文件流接口外，响应统一为：

```json
{
  "success": true,
  "message": "OK",
  "code": "OK",
  "data": {},
  "requestId": null
}
```

错误响应仍使用相同结构。客户端展示中文 `message`，记录 `requestId` 供排查，不向业务用户直接展示英文技术码。

所有受保护请求发送：

```http
Authorization: Bearer <opaque-token>
```

必须处理的状态：

| HTTP | 语义 | 客户端行为 |
| --- | --- | --- |
| 400 | 参数或业务输入不正确 | 保留表单并显示中文原因 |
| 401 | 未登录、Token 过期或权限版本失效 | 清除登录会话并回登录页 |
| 403 | 角色、门店、仓库或附件越权 | 禁止重试，不回退为前端数据 |
| 409 | 并发状态变化或幂等键冲突 | 刷新服务端记录后提示用户 |
| 413 | 上传超过服务端限制 | 提示压缩或重新选择文件 |
| 5xx | 服务端或依赖暂不可用 | 保留未提交表单内存状态，允许人工重试 |

生产 H5 必须使用 HTTPS，并在 `app.cors.allowed-origin-patterns` 中显式配置正式域名；小程序和 App 也必须配置 HTTPS 合法域名，不得为发布方便开放任意来源。

## 3. 登录、会话、角色与当前门店

### 3.1 登录与退出

| 方法 | 路径 | 请求 | 响应 |
| --- | --- | --- | --- |
| POST | `/api/auth/login` | `{username,password,tenantId?}` | `{token,user}` |
| GET | `/api/auth/me` | Bearer Token | `SessionUser` |
| POST | `/api/auth/logout` | Bearer Token | 空成功响应，服务端撤销 Token |
| GET | `/api/session/me` | Bearer Token | 桌面兼容别名；新移动端使用 `/api/auth/me` |

`SessionUser` 是移动菜单和当前数据范围的唯一会话依据，关键字段包括：

- `role`、`roleLabel`
- `permissions`
- `dataScopes`
- `boundStoreId`、`boundStoreName`
- `brandId`、`brandName`
- `defaultWorkspace`
- `permissionVersion`

`SUPERVISOR` 历史角色在认证层会规范为 `OPERATIONS`。移动菜单不得仅比较角色字符串，应同时以 `permissions` 和对应 `dataScopes` 为准。

### 3.2 门店和仓库范围

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/stores` | 返回当前用户数据范围内的门店 |
| GET | `/api/warehouse/warehouses` | 返回当前用户可见、启用的仓库 |
| GET | `/api/warehouse/overview?warehouseId=` | 返回经权限裁剪的库存工作台 |
| GET | `/api/warehouse/transfers/context?warehouseId=` | 返回当前用户可执行的调拨路线和动作 |

前端传入的 `storeId`、`warehouseId` 仅是请求条件，不是授权依据。服务端仍会校验租户、权限和数据范围；跨范围访问返回 403 并写权限拒绝日志。

### 3.3 移动菜单判定

客户端可以内置页面路由和“权限码到页面”的展示映射，但是否显示入口必须来自 `/api/auth/me`：

| 一期入口 | 必要权限/范围 |
| --- | --- |
| 店长库存 | `warehouse.store.read` + `WAREHOUSE` 范围 |
| 店长叫货 | `warehouse.requisition.create` + 本店范围 |
| 店长收货 | `warehouse.requisition.receive` + 本店范围 |
| 督导巡检 | `inspection.read` / `inspection.manage` + `INSPECTION` 范围 |
| 培训考试 | `exam.learn` + `EXAM` 范围 |
| 员工助手 | `employee_assistant.use` |
| 财务摘要 | `finance.profit.read` + `FINANCE` 范围 |
| 老板摘要 | `system.dashboard.read` + `FINANCE` 范围；BOSS 默认全范围 |

隐藏菜单不能替代后端校验。客户端不得因为拥有路由地址就认为用户有权调用接口。

## 4. 店长一期闭环

### 4.1 库存和叫货单

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/warehouse/overview` | 本店可见库存、预警和叫货摘要；成本字段由后端裁剪 |
| GET | `/api/warehouse/items` | 当前供货仓可叫货物料 |
| GET | `/api/warehouse/requisitions` | 当前账号范围内的叫货单 |
| POST | `/api/warehouse/requisitions` | 提交叫货单 |
| POST | `/api/warehouse/requisitions/{id}/receive` | 门店确认收货 |

叫货请求：

```json
{
  "storeId": "rg1",
  "lines": [
    {"itemId": 101, "requestedQuantity": 2, "note": "周末备货"}
  ],
  "note": "手机端叫货",
  "clientRequestId": "req-<uuid>"
}
```

移动端必须为每次业务意图生成稳定的 `clientRequestId`，弱网重试沿用同一值。相同键返回原叫货单，不重复插入 MySQL 或操作日志。数量修改在提交前只存在页面内存中；一旦提交，以服务端返回的叫货单为准。

确认收货请求可带 `{ "note": "数量核对无误" }`。服务端锁定叫货单并校验本店范围；已收货记录再次确认会直接返回成功，不重复增加门店库存。

## 5. 督导一期闭环

### 5.1 任务、记录和标准

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/supervisor/todos?includeDone=&status=&brandId=&storeId=` | 督导待办和整改任务 |
| GET | `/api/inspections?...` | 数据范围内巡检记录 |
| GET | `/api/inspections/{id}` | 巡检详情和整改状态 |
| GET | `/api/inspection/standards` | 当前生效巡检标准 |
| POST | `/api/inspections` | 桌面兼容创建入口 |
| PUT | `/api/inspections/{id}` | 移动端按稳定 ID 创建/更新巡检记录 |

跨店读取或保存由 `INSPECTION` 数据范围拦截并返回 403。移动端不得以门店下拉框过滤代替服务端授权。

### 5.2 拍照、上传、AI 建议和人工确认

新巡检照片先通过正式附件服务上传：

```http
POST /api/storage/upload
Content-Type: multipart/form-data

file=<original image>
businessType=INSPECTION_RECORD
businessId=inspection-<storeId>-draft
storeId=<storeId>
```

响应 `data.id` 是附件 ID。巡检保存时必须把附件 ID 明确关联到具体巡检条款，不能只保存文件名、Base64 或平台临时路径。

AI 辅助流程：

1. `POST /api/inspections/detect`，multipart `file`。该接口在服务端完成识别后立即调用正式条款匹配逻辑，返回带 `detectionKey` 的建议；前端拿不到可自行决定最终扣分的原始结果。
2. `POST /api/inspections/detection-suggestions` 是同一套只读匹配逻辑的批量/重新匹配入口，不保存分数；移动端单图流程无需重复调用。
3. `POST /api/inspections/detection-suggestions/{detectionKey}/confirm`，人工确认草稿建议；服务端根据原始证据重新匹配并校验编号。
4. 保存巡检记录和条款结果；服务端再次按当前正式标准重算最终得分，不接受前端伪造条款或扣分。
5. 已保存记录也可使用 `/api/inspections/{id}/detection-results` 绑定结果，并通过 `/detections/{detectionKey}/confirm`、`/revoke`、`/manual-adjust` 进行有审计的人工决定。

历史证据补传使用专用的 `POST /api/inspections/{id}/evidence/upload`；通用上传接口不会绕过历史证据关联规则。

下载使用 `GET /api/storage/attachments/{id}`。服务端同时检查租户、附件权限、门店范围、业务记录归属和草稿上传人；越权返回 403。

## 6. 员工一期闭环

### 6.1 培训视频

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/exam-center/videos` | 可见培训视频及本人进度 |
| GET | `/api/exam-center/videos/{videoId}/content` | Bearer 认证视频流，保留给可携带请求头的桌面端/App，支持 `Range` 和 206 |
| POST | `/api/exam-center/videos/{videoId}/playback-ticket` | 使用当前 Bearer 换取 30 分钟、仅绑定当前视频和会话的临时播放路径 |
| GET | `/api/exam-center/videos/{videoId}/stream?ticket=...` | H5/微信播放器使用的临时流；每个 Range 请求仍回查原会话、权限版本和视频范围 |
| POST | `/api/exam-center/videos/{videoId}/progress` | 上报播放位置和时长 |

进度请求为 `{positionSeconds,durationSeconds}`。服务端按用户和视频落 MySQL；端侧缓存只能用于播放器瞬时恢复，不能作为正式学习进度。

App 的 UniApp 原生 `video` 组件通过 `header` 携带 Bearer；H5 和微信小程序的播放器不把主 Bearer 放入 URL，而是使用高熵临时票据直接发起 Range 请求。票据仅存后端内存、禁止缓存、同一会话同一视频重新签发时旧票据失效；退出登录、账号停用或权限版本变化后，后续分段请求立即返回 401。播放器遇到临时票据过期只自动重新申请一次，不能无限重试。

当前票据存储是单实例实现。多后端实例发布前必须启用同一实例粘性会话，或改为带 TTL 的共享 Redis 票据并保留会话回查；未完成该门禁不得把 H5/微信流量无粘性地分发到多个实例。应用日志和操作日志只记录视频与字节范围，不记录票据或主 Bearer；网关和访问日志还必须关闭查询串记录或对 `ticket` 参数脱敏。

三端标准播放器应发送 `Range` 并获得 206。若客户端或反向代理剥离 `Range`，兼容接口仍可能一次返回最多 20MB；发布验收必须通过真实域名检查请求头、`Content-Range`、断网续播和拖动进度，不能只以本地单元测试替代。

### 6.2 考试和成绩

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/exam-center/overview` | 本人考试任务；服务端返回 `accessMode` 和可执行范围 |
| GET | `/api/exam-center/assignments/{assignmentId}/paper` | 获取本人试卷 |
| POST | `/api/exam-center/assignments/{assignmentId}/submit` | 交卷 |
| GET | `/api/exam-center/results` | 本人成绩 |
| GET | `/api/exam-center/wrong-questions` | 本人错题 |

交卷请求：

```json
{
  "violated": false,
  "answers": [
    {"questionId": 1, "userAnswer": "A"}
  ]
}
```

服务端锁定考试任务、校验任务归属和考试时间、读取服务端标准答案并计算分数。弱网导致同一任务重复交卷时，若第一次已完成、待阅卷或处于违规待重考状态，接口返回第一次生成的 `ExamAttemptResponse`，不会创建第二条成绩或重复写提交日志。

### 6.3 员工服务助手

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/employee-assistant/status` | 返回安全的可用状态，不暴露上游地址或 Token |
| POST | `/api/employee-assistant/chat` | `{sessionId,message}` |

响应包含 `answer`、`configured`、`sessionId`、`needsHuman`、`answerSource` 和知识版本信息。前端不得保存上游密钥，也不得把附件或无关业务记录转发到助手。

## 7. 财务和老板只读摘要

### 财务

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/finance/workbench?month=&brandId=&storeId=` | 财务待办和摘要 |
| GET | `/api/finance/months` | 可选月份 |
| GET | `/api/finance/dashboard?month=&brandId=&storeId=` | 经营摘要 |
| GET | `/api/finance/entries?...` | 权限范围内的只读明细 |

### 老板

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/boss/todos` | 老板待办摘要 |
| GET | `/api/boss/data-health` | 数据健康摘要 |
| GET | `/api/boss/exam-summary` | 培训考试摘要 |
| GET | `/api/finance/dashboard` | 全范围经营摘要 |

一期移动端不得暴露下列桌面复杂操作入口：经营录入、月度导入、账号权限管理、仓库配置、采购配置、批量导出和巡检 Excel 导出。即使移动端未显示入口，后端仍按既有权限检查。

## 8. 幂等与并发契约

| 业务动作 | 机制 | 重试结果 |
| --- | --- | --- |
| 创建叫货单 | `clientRequestId` + 服务端去重表 | 返回原叫货单 |
| 门店确认收货 | 叫货单行锁 + `RECEIVED` 状态 | 不重复增加库存 |
| 创建仓间调拨 | 必填 `clientRequestId` +唯一键 | 返回原调拨单 |
| 调拨提交/发货/收货 | 动作幂等键、状态版本和库存行锁 | 返回既有状态或明确冲突 |
| 考试交卷 | 考试任务行锁 + 已生成 `attemptId` | 返回第一次成绩 |
| 巡检保存 | 每次业务意图生成稳定记录 ID + `PUT /api/inspections/{id}` upsert | 响应丢失后先按同一 ID 查询，重试不创建第二条记录 |
| 视频进度 | 用户+视频进度更新 | 最终以服务端进度为准 |

幂等键只代表一次业务意图。用户修改表单后重新提交必须生成新键；同一键用于不同仓库、门店或业务单据时返回 409。

库存审批、发货、收货继续使用现有 MySQL 事务、`FOR UPDATE` 行锁、版本号和服务端库存计算。移动端展示的库存只是提示，不能作为扣减依据。

## 9. 安全和日志

- 所有 Controller 从 `Authorization` 解析当前用户；无 Token 返回 401。
- 权限拒绝、跨店、跨仓和越权附件访问返回 403；服务端写权限拒绝日志。
- 上传绑定真实租户、门店、业务类型和业务 ID；上传操作写 `operation_log`。
- 叫货、收货、巡检确认、考试交卷和其它关键状态变化使用既有业务操作日志。
- 数据库密码、AI Key、助手 Token、推送证书和上传密钥只允许由后端环境变量或部署密钥系统提供。
- 移动端构建变量只能包含非敏感 API 基地址和公开版本信息。
- API 日志不得打印 Authorization、密码、上传原图内容、AI Key 或推送证书。

### 9.1 App 版本检查

Android/iOS 登录后可调用：

```http
GET /api/mobile/version?platform=android&version=0.1.0
Authorization: Bearer <opaque-token>
```

`platform` 只接受 `android` 或 `ios`；`version` 是可选的当前安装版本。响应：

```json
{
  "currentVersion": "0.1.0",
  "minimumVersion": "0.1.0",
  "updateAvailable": false,
  "forceUpdate": false,
  "downloadUrl": null,
  "message": "当前已是可用版本"
}
```

版本元数据只来自 `MOBILE_ANDROID_*`、`MOBILE_IOS_*` 环境变量。默认不提示更新、不强更且无下载地址。只有可解析、带主机名的绝对 `https` 下载地址才允许触发更新；空地址、相对地址、`http:` 与 `javascript:` 均返回 `updateAvailable=false`、`forceUpdate=false`，避免误配置锁死客户端或打开不安全链接。接口不读取推送证书、应用签名、商店密钥，也不写数据库。

## 10. 契约验证

后端新增 `MobileApiContractTest` 固定以下 HTTP 契约：

- 未登录访问 `/api/auth/me` 返回 401。
- 跨店巡检返回 403。
- 跨仓调拨上下文返回 403。
- 跨店附件读取返回 403。
- 同一 `clientRequestId` 重复提交店长叫货返回同一 REQ；调拨创建也保留相同 HTTP 契约。
- 同一移动巡检记录 ID 重复 `PUT` 始终落到同一记录；响应丢失后可按该 ID 读取确认。
- App 版本接口要求登录、拒绝未知平台，并在下载地址不是绝对 HTTPS 时禁止更新和意外强更。

`WarehouseServiceTest` 用真实服务和测试数据库验证重复叫货只新增一张单、只写一次操作日志；`ExamLearningFlowTest` 验证重复交卷返回同一成绩且数据库只保留一条考试记录。现有仓库集成测试继续覆盖调拨、库存并发和其它幂等场景。

本契约不授权生产数据库操作、生产发布、小程序审核或应用商店上传。
