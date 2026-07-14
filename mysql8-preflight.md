# MySQL 8 测试实例安装前审计

审计时间：2026-07-10（Asia/Shanghai）

## Windows

| 项目 | 结果 |
|---|---|
| 系统 | Microsoft Windows 11 家庭版 中文版 |
| 系统版本 | 10.0.26200 |
| 系统架构 | 64-bit / x64 |
| Visual C++ x64 Runtime | 已安装，v14.44.35211.00 |

## 现有 MySQL 5.5

| 项目 | 结果 |
|---|---|
| 版本 | MySQL Community Server 5.5.62 x64 |
| Windows 服务 | `MySQL` |
| 服务状态 | Running |
| 启动类型 | Auto |
| 服务程序 | `D:\Program Files\bin\mysqld` |
| 配置文件 | `D:\Program Files\my.ini` |
| 程序目录 | `D:\Program Files\` |
| 数据目录 | `C:\ProgramData\MySQL\MySQL Server 5.5\Data\` |
| TCP 端口 | 3306，审计时由 PID 5300 监听 |

## 新测试实例隔离方案

| 项目 | 计划值 |
|---|---|
| 版本 | MySQL Community Server 8.0 x64 |
| Windows 服务 | `MySQL80Test` |
| 启动类型 | Manual |
| TCP 端口 | 3307 |
| X Protocol 端口 | 33070 |
| 绑定地址 | `127.0.0.1` |
| 程序目录 | MySQL Installer 提供的独立 MySQL Server 8.0 目录 |
| 数据目录 | 独立的 `MySQL Server 8.0 Test` 数据目录，不复用 5.5 数据目录 |

审计时 3307 和 33070 均未被占用。新实例的服务名、端口、程序目录和数据目录均与 MySQL 5.5 分离，可以继续安装。

## 安全边界

- 不升级、覆盖、卸载或重新配置 MySQL 5.5。
- 不读取或迁移 MySQL 5.5 业务数据。
- 不连接生产数据库。
- 安装器 UAC、服务注册确认和 root 密码由用户本人完成。
- 本报告不记录任何密码、Token 或连接字符串。
