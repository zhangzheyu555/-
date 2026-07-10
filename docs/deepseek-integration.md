# DeepSeek 接入文档

## 概述

门店经营助手通过 DeepSeek API 提供 AI 增强分析。当 DeepSeek 不可用时，系统自动降级到本地财务数据回答。

## 架构

```
Vue3 前端 → POST /api/assistant/chat → Spring Boot AssistantService
                                            ↓
                                    DeepSeek API (stream=false)
                                    POST /chat/completions
                                            ↓
                                    读取 choices[0].message.content
                                            ↓
                            ← AssistantChatResponse (含 source/fallback/model)
```

## 环境变量

| 变量 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `DEEPSEEK_ENABLED` | 否 | `true` | 是否启用 DeepSeek |
| `DEEPSEEK_API_KEY` | **是** | 无 | DeepSeek API Key |
| `DEEPSEEK_BASE_URL` | 否 | `https://api.deepseek.com` | DeepSeek API 地址 |
| `DEEPSEEK_MODEL` | 否 | `deepseek-v4-flash` | 默认模型 |
| `DEEPSEEK_MAX_TOKENS` | 否 | `1200` | 最大输出 Token |
| `DEEPSEEK_TEMPERATURE` | 否 | `0.2` | 温度参数 |
| `DEEPSEEK_CONNECT_TIMEOUT` | 否 | `5s` | 连接超时 |
| `DEEPSEEK_TIMEOUT` | 否 | `45s` | 响应超时 |

**复杂分析场景**可通过 `DEEPSEEK_MODEL=deepseek-v4-pro` 切换到专业版模型。

## 启动方式

### Windows (PowerShell)
```powershell
$env:DEEPSEEK_ENABLED="true"
$env:DEEPSEEK_API_KEY="sk-xxxxxxxxxxxxxxxx"
$env:DEEPSEEK_BASE_URL="https://api.deepseek.com"
$env:DEEPSEEK_MODEL="deepseek-v4-flash"
$env:MYSQL_PASSWORD="<MySQL密码>"
java -jar backend\target\store-profit-backend-0.1.0-SNAPSHOT.jar
```

### Linux/macOS
```bash
export DEEPSEEK_ENABLED=true
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx
export DEEPSEEK_BASE_URL=https://api.deepseek.com
export DEEPSEEK_MODEL=deepseek-v4-flash
export MYSQL_PASSWORD=<MySQL密码>
java -jar backend/target/store-profit-backend-0.1.0-SNAPSHOT.jar
```

### 生产环境（systemd）
在 service 文件中配置环境变量：
```
[Service]
Environment="DEEPSEEK_ENABLED=true"
Environment="DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx"
Environment="DEEPSEEK_BASE_URL=https://api.deepseek.com"
Environment="DEEPSEEK_MODEL=deepseek-v4-flash"
```

## API 接口

### POST /api/assistant/chat

经营助手聊天接口，自动路由到 DeepSeek 或本地数据。

**响应字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `answer` | string | 最终展示回答 |
| `localAnswer` | string | 本地数据回答 |
| `deepSeekAnswer` | string\|null | DeepSeek 原始回答 |
| `deepSeekAvailable` | boolean | DeepSeek 是否可用 |
| `source` | string | 数据来源标识 |
| `model` | string | 实际使用的模型（本地回答为空） |
| `fallback` | boolean | 是否发生降级 |
| `fallbackReason` | string | 降级原因（仅限安全错误码） |
| `requestId` | string | 请求追踪 ID（8 位） |
| `generatedAt` | string | 生成时间（ISO 8601） |

### GET /api/assistant/status

**仅限 ADMIN/OWNER/BOSS** 访问的诊断接口。

**响应字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `enabled` | boolean | 是否启用 |
| `configured` | boolean | API Key 是否已配置 |
| `baseUrlHost` | string | DeepSeek 主机名（不含 Key） |
| `model` | string | 当前模型 |
| `lastSuccessAt` | string\|null | 上次成功时间 |
| `lastFailureAt` | string\|null | 上次失败时间 |
| `lastFailureCode` | string\|null | 上次失败错误码 |

**状态接口不会触发付费 API 调用。**

## 错误码

| 错误码 | HTTP 状态 | 触发条件 | 重试 |
|--------|-----------|----------|------|
| `DEEPSEEK_NOT_CONFIGURED` | — | Key 为空或 enabled=false | 否 |
| `DEEPSEEK_DISABLED` | — | enabled=false | 否 |
| `DEEPSEEK_AUTH_FAILED` | 401 | API Key 无效 | 否 |
| `DEEPSEEK_BALANCE_INSUFFICIENT` | 402 | 账户余额不足 | 否 |
| `DEEPSEEK_INVALID_REQUEST` | 400/422 | 请求参数错误 | 否 |
| `DEEPSEEK_RATE_LIMITED` | 429 | 频率限制 | **是（最多 2 次）** |
| `DEEPSEEK_TIMEOUT` | — | 连接/响应超时 | **是（最多 2 次）** |
| `DEEPSEEK_UNAVAILABLE` | 500/503 | 服务端错误 | **是（最多 2 次）** |
| `DEEPSEEK_EMPTY_RESPONSE` | 200 | content 为空 | 否 |
| `DEEPSEEK_RESPONSE_INVALID` | — | JSON 解析失败 | 否 |

**重试策略**：仅 429/500/503/超时重试，指数退避（500ms、1000ms），最多 3 次尝试。

## 安全要求

1. **API Key 只存在于后端运行环境**，不得写入 application.yml、Vue3、文档或 Git。
2. API Key **不得返回给前端**，响应中不包含 Authorization Header。
3. 日志中**不打印 Authorization Header**。
4. **不得在浏览器中直接请求 DeepSeek**，所有 AI 请求通过后端代理。
5. 只发送当前用户有权查看的门店和月份汇总数据。
6. 经营助手保持**只读**，不允许 AI 修改利润、工资、库存和审批状态。
7. 每次调用记录用户、租户、门店、模型、耗时、结果状态和 requestId，不记录密钥。
8. 普通用户看到安全中文错误提示，管理员可通过 status 接口诊断。

## 数据发送规则

发送给 DeepSeek 的数据**仅包含**：
- 当前用户有权查看的门店汇总数据
- 营业额、实收收入、成本、费用、净利润、净利率、风险状态
- 门店名称、品牌、区域

**绝不发送**：
- 员工姓名、工资明细
- 密码、Token、API Key
- 附件内容
- 其他租户数据
- 完整数据库查询结果

## 模型切换

默认使用 `deepseek-v4-flash`（快速推理），复杂分析可切换为 `deepseek-v4-pro`：

```powershell
$env:DEEPSEEK_MODEL="deepseek-v4-pro"
```

不同请求可使用不同模型，由环境变量控制。

## 降级行为

当 DeepSeek 不可用时（未配置、认证失败、网络故障等）：
1. 系统自动回退到本地财务数据回答
2. 响应中 `fallback=true`，`source` 指明数据来源
3. `fallbackReason` 提供安全错误码
4. 前端根据 `fallbackReason` 显示安全中文提示
5. 本地数据展示照常进行

## 相关文件

- `backend/src/main/java/com/storeprofit/system/assistant/AssistantService.java` — 核心逻辑
- `backend/src/main/java/com/storeprofit/system/assistant/AssistantController.java` — API 端点
- `backend/src/main/java/com/storeprofit/system/assistant/DeepSeekProperties.java` — 配置管理
- `backend/src/main/java/com/storeprofit/system/assistant/DeepSeekException.java` — 错误分类
- `backend/src/main/java/com/storeprofit/system/assistant/AssistantChatResponse.java` — 响应结构
- `backend/src/main/java/com/storeprofit/system/assistant/AssistantStatusResponse.java` — 状态响应
- `backend/src/main/resources/application.yml` — 默认配置
- `frontend-vue/src/api/assistant.ts` — 前端 API 接口
- `frontend-vue/src/pages/AssistantPage.vue` — 经营助手页面
