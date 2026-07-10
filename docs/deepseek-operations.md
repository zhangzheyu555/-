# DeepSeek 运维手册

## 日常检查

### 检查 DeepSeek 状态
```http
GET /api/assistant/status
Authorization: Bearer <BOSS_TOKEN>
```

响应示例（正常）：
```json
{
  "success": true,
  "data": {
    "enabled": true,
    "configured": true,
    "baseUrlHost": "api.deepseek.com",
    "model": "deepseek-v4-flash",
    "lastSuccessAt": "2026-07-10T10:30:00Z",
    "lastFailureAt": null,
    "lastFailureCode": null
  }
}
```

响应示例（未配置）：
```json
{
  "success": true,
  "data": {
    "enabled": true,
    "configured": false,
    "baseUrlHost": "api.deepseek.com",
    "model": "deepseek-v4-flash",
    "lastSuccessAt": null,
    "lastFailureAt": "2026-07-10T10:00:00Z",
    "lastFailureCode": "DEEPSEEK_NOT_CONFIGURED"
  }
}
```

## 密钥管理

### 密钥轮换
1. 在 DeepSeek 控制台生成新 Key
2. 更新部署环境变量 `DEEPSEEK_API_KEY`
3. 重启后端服务
4. 验证 status 接口 `configured: true`
5. 在 DeepSeek 控制台删除旧 Key

### 禁止事项
- ❌ 不得将 Key 写入 application.yml
- ❌ 不得将 Key 提交到 Git
- ❌ 不得将 Key 写入文档或测试代码
- ❌ 不得在前端代码中硬编码 Key
- ❌ 不得在日志中打印 Authorization Header

## 日志定位

### 成功日志
```
INFO  AssistantService - DeepSeek success. requestId=abc12345 elapsedMs=2340 model=deepseek-v4-flash storeId=S001 storeName=荆州之星 month=2026-07 intent=net_profit
```

### 失败日志
```
ERROR AssistantService - DeepSeek error. requestId=def67890 code=DEEPSEEK_AUTH_FAILED elapsedMs=150 status=401 model=deepseek-v4-flash storeId=S001 storeName=荆州之星 month=2026-07 intent=net_profit
```

### 未配置日志
```
WARN  AssistantService - DeepSeek unavailable: code=DEEPSEEK_NOT_CONFIGURED requestId=ghi11111 baseUrl=https://api.deepseek.com model=deepseek-v4-flash storeId= storeName= month= intent=
```

### 重试日志
```
WARN  AssistantService - DeepSeek retry 1/3: code=DEEPSEEK_RATE_LIMITED status=429 backoffMs=500 model=deepseek-v4-flash
```

## 故障处理

### 问题：页面显示"AI 服务尚未配置"
**原因**：DEEPSEEK_API_KEY 环境变量未设置或为空。
**解决**：设置有效的 API Key 并重启服务。

### 问题：页面显示"AI 服务认证失败"
**原因**：API Key 无效或已过期。
**解决**：检查 DeepSeek 控制台，确认 Key 状态，必要时轮换。

### 问题：页面显示"AI 账户余额不足"
**原因**：DeepSeek 账户余额不足。
**解决**：在 DeepSeek 控制台充值。

### 问题：页面显示"请求过于频繁"
**原因**：触发 DeepSeek 频率限制（429）。
**解决**：系统会自动重试（最多 2 次），如持续失败需降低请求频率。

### 问题：页面显示"AI 服务响应超时"
**原因**：网络问题或 DeepSeek 服务延迟。
**解决**：检查网络连接，确认 `https://api.deepseek.com` 可达。系统会自动重试。

### 问题：页面显示"AI 服务暂时不可用"
**原因**：DeepSeek 返回 500/503。
**解决**：等待 DeepSeek 恢复，系统会自动重试。可查看 DeepSeek 状态页。

## 临时禁用 DeepSeek

如需临时禁用（如账户余额不足时保留本地功能）：

```powershell
$env:DEEPSEEK_ENABLED="false"
```

重启后 status 接口返回 `enabled: false`，所有请求降级到本地数据。

## 监控指标

关注以下日志模式：

| 指标 | 日志关键字 | 建议 |
|------|-----------|------|
| 成功率 | `DeepSeek success` | 应 > 95% |
| 认证失败 | `DEEPSEEK_AUTH_FAILED` | 立即检查 Key |
| 余额不足 | `DEEPSEEK_BALANCE_INSUFFICIENT` | 立即充值 |
| 频繁限流 | `DEEPSEEK_RATE_LIMITED` | 控制请求频率 |
| 超时增多 | `DEEPSEEK_TIMEOUT` | 检查网络/考虑增大超时 |
| 服务不可用 | `DEEPSEEK_UNAVAILABLE` | 关注 DeepSeek 状态 |

## 成本控制

1. 默认 `max_tokens=1200`，控制每次调用 Token 消耗
2. 默认 `temperature=0.2`，减少随机性降低重复调用
3. 前端有重复点击保护，防止连续发送
4. 系统级守卫（屏蔽词、越界检查）在调用前拦截无效请求
5. status 接口不产生 API 调用费用

## 网络要求

后端需要能够访问：
- `https://api.deepseek.com`（443 端口）
- DNS 解析正常
- TLS 1.2+

如果通过代理访问，设置 Java 系统属性：
```bash
java -Dhttps.proxyHost=proxy.example.com -Dhttps.proxyPort=8080 -jar ...
```
