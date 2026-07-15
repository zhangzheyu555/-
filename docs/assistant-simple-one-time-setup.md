# 双助手一次配置（仅 root 密码和一个 DeepSeek Key）

当本机没有既有的 MySQL 应用账号密码、但维护人员持有 MySQL 3307 root 密码时，使用该入口完成一次受控部署：

```powershell
& .\scripts\configure-and-start-assistants-simple.ps1
```

它只会在当前 Windows 用户的隐藏输入框中请求：

1. MySQL `3307` root 密码；只用于创建或轮换一个本机受限运行账号，后端不会使用 root 运行。
2. 一个 DeepSeek API Key；先执行一条不含业务数据的实际模型预检，再分别写入门店经营助手和员工服务助手的独立运行变量。

默认模型地址为 `https://api.deepseek.com`，默认模型为 `deepseek-v4-pro`。如实际模型名不同，可安全地将模型名作为非密钥参数传入：

```powershell
& .\scripts\configure-and-start-assistants-simple.ps1 -ModelName '<已批准模型名>'
```

运行时会执行以下门禁：

- DeepSeek `chat/completions` 静态探针成功，且不发送门店、员工或客户业务数据；
- 创建或轮换的 MySQL 账号仅限 `127.0.0.1` 和目标业务库，且没有 `GRANT OPTION`；
- 18082 候选通过双上游、健康检查和两个未登录接口 `401` 门禁；
- 只有输入 `REPLACE_18081_CONFIRM` 后才替换 18081。

API Key、root 密码、运行账号密码都不会写入源码、命令行、日志、Git 或前端。助手 Key 仅以当前 Windows 用户可解密的 DPAPI 文件保存；运行账号密码只在本次启动进程内保留。

若 API Key 曾经出现在聊天、截图或终端，请先在供应商后台轮换为新 Key，再在隐藏输入中填写新值。
