# 双助手统一安全部署

## 目的

门店经营助手和员工服务助手使用不同的上游、权限和数据边界，但每次候选或正式 Java 启动都必须同时具备两套配置。这样不会再出现“上线员工助手后，门店经营助手丢失 `DEEPSEEK_API_KEY`”的情况。

| 助手 | 进程变量 | 数据边界 |
| --- | --- | --- |
| 门店经营助手 | `DEEPSEEK_ENABLED`、`DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL`、`DEEPSEEK_API_KEY` | 仅本地经营数据分析；保留“本地数据查询可用、AI 分析不可用”的中文降级能力。 |
| 员工服务助手 | `EMPLOYEE_ASSISTANT_*` | 只处理脱敏员工常见问题和已发布标准话术；不读取 `DEEPSEEK_*`、门店财务、客户隐私或附件。 |

员工助手只允许下列唯一模式之一：

```text
# REMOTE
EMPLOYEE_ASSISTANT_PROVIDER=REMOTE
EMPLOYEE_ASSISTANT_URL=<上游基础地址>
EMPLOYEE_ASSISTANT_API_TOKEN=<专属令牌>

# MODEL
EMPLOYEE_ASSISTANT_PROVIDER=MODEL
EMPLOYEE_ASSISTANT_MODEL_URL=<模型基础地址>
EMPLOYEE_ASSISTANT_MODEL_NAME=<已批准模型名>
EMPLOYEE_ASSISTANT_MODEL_API_KEY=<专属 API Key>
```

两种员工模式不得混用。两套助手也不得互相读取、复制或回退凭据。

## 当前 Windows 用户 DPAPI 安全配置

先运行一次配置入口。它只在本机通过隐藏输入读取两个助手的 API Key/令牌，将两套完整配置加密保存到当前 Windows 用户的 `%LOCALAPPDATA%\AI-Profit-OS\runtime-config\assistant-providers.v1.dpapi`；文件不在仓库中，且不能由其他 Windows 用户直接解密。

```powershell
# 地址和模型名不是密钥，可作为参数提供；两个凭据始终通过隐藏输入获得。
& .\scripts\configure-assistant-runtime-config.ps1 `
  -EmployeeAssistantMode MODEL `
  -BusinessAssistantUrl <门店经营助手模型基础地址> `
  -BusinessAssistantModel <门店经营助手模型名> `
  -EmployeeAssistantModelUrl <员工助手模型基础地址> `
  -EmployeeAssistantModelName <员工助手模型名>
```

REMOTE 模式使用 `-EmployeeAssistantMode REMOTE -EmployeeAssistantRemoteUrl <上游基础地址>`。URL 仅接受无账号信息、查询参数、片段和操作路径的 `http`/`https` 基础地址；可包含通用 `/v1` 前缀，但不能以 `/models`、`/chat/completions` 或 `/api/v1/health` 结尾。

不要在命令行、环境变量、源码、前端、日志、Git、`.env` 或聊天中传入 API Key、令牌或数据库密码。数据库密码不会持久化，每次启动仍通过本机隐藏输入读取。

配置完成后执行只读预检：

```powershell
& .\scripts\verify-employee-assistant-config.ps1
if ($LASTEXITCODE -ne 0) { throw '双助手 DPAPI 配置未通过安全检查。' }
```

预检只报告两套配置完整性、员工模式和地址格式；不会显示路径、地址、模型、令牌、Key 或环境变量原值。

## 候选与受控替换

使用既有的非 root MySQL 应用账号运行统一入口。不要通过旧 Java 进程、系统环境变量或单助手脚本传递配置。

```powershell
# 先在 18082 候选；不会停止或重启当前 18081。
& .\scripts\start-backend-assistants-secure.ps1 `
  -MySqlHost 127.0.0.1 -MySqlPort 3307 `
  -MySqlDatabase <目标库> -MySqlUsername <既有非root应用账号> `
  -CandidatePort 18082 -JarPath <不可变JAR绝对路径>

# 只有维护人员已确认候选并计划切换时，才增加此开关；脚本仍会要求精确确认词。
& .\scripts\start-backend-assistants-secure.ps1 `
  -MySqlHost 127.0.0.1 -MySqlPort 3307 `
  -MySqlDatabase <目标库> -MySqlUsername <既有非root应用账号> `
  -CandidatePort 18082 -JarPath <不可变JAR绝对路径> -PromoteTo18081
```

统一启动器在每次启动 Java 前都会：

1. 从同一 Windows 用户的 DPAPI 配置源读取并验证两套配置；正式 18081 会在停旧实例前再次读取和验证，绝不复制候选或旧 Java 环境。
2. 从子进程环境清空继承项，只保留 Windows/JVM 必需白名单和显式部署变量，再分别注入 `DEEPSEEK_*` 和 `EMPLOYEE_ASSISTANT_*`。这也阻断 `APP_ASSISTANT_DEEPSEEK_*`、`APP_EMPLOYEE_ASSISTANT_*`、`SPRING_APPLICATION_JSON`、`SPRING_CONFIG_*`、`JAVA_TOOL_OPTIONS`、`JDK_JAVA_OPTIONS`、`_JAVA_OPTIONS` 等父环境覆盖。
3. 用内存中的专属凭据检查两个上游：门店经营助手调用 `/models`；员工 REMOTE 调用 `/api/v1/health`，员工 MODEL 调用 `/models`。输出仅会是 `READY`、`AUTH_FAILED` 或 `UNAVAILABLE`，不会显示上游细节。
4. 在 `18082` 验证 `/api/health=UP`、预期 MySQL/Flyway/受限账号范围，以及未登录的 `/api/assistant/status` 和 `/api/employee-assistant/status` 都在 5 秒内返回 `401`。

候选任一门禁失败时会结束候选并保留旧 `18081`。只有候选、两套上游和第二次 DPAPI 读取均通过，且操作者输入 `REPLACE_18081_CONFIRM`，才会停止经工作目录和 JAR 核验的旧 Java。

切换确认后若新 `18081` 启动或最终门禁失败，脚本会停止失败的新实例，但保留已通过门禁的 `18082` 候选以便恢复；旧进程不会自动复活。此时应停止继续操作，按现场恢复流程决定是否将候选提升或恢复旧不可变 JAR，不得做修复性猜测。

## 登录后验收与旧入口

候选/正式过程只验证未登录 `401`，不会读取会话令牌。维护人员应在已登录页面检查两套助手的中文状态；门店经营助手仍可在 AI 不可用时查询本地数据，员工助手只显示“未配置”“已就绪”“授权异常”或“暂时不可用”。

下列旧入口已经安全弃用，调用时会拒绝执行，不能再单独启动候选、18081 或创建数据库账号：

- `configure-employee-assistant-once.ps1`
- `start-backend-employee-assistant-model-secure.ps1`
- `start-backend-deepseek-secure.ps1`
- `start-backend-v43-secure.ps1`
- `bootstrap-runtime-account-and-start-v43.ps1`

`start-backend-windows.ps1` 是历史 18082 预验收工具，不属于当前助手部署流程；不得用它替代统一入口。历史 MySQL/Flyway 工具同样不属于本流程，且本流程不会创建账号、修改 MySQL、执行 Flyway repair 或改写迁移。
