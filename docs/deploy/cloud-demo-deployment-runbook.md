# 云端演示环境部署与 47 个账号初始化 Runbook

本文只适用于 `APP_ENV=STAGING` 或 `APP_ENV=DEMO` 的演示环境。生产环境禁止自动创建固定账号，禁止继续使用统一临时密码 `12345678`。

## 安全边界

- 不通过 Flyway 创建固定账号。
- 不把云服务器 SSH 密钥、数据库密码、API key、Boss Token 写入仓库。
- 47 个账号必须通过后端 API 或受控初始化脚本创建。
- 统一密码 `12345678` 只允许在 `APP_ENV=STAGING` 或 `APP_ENV=DEMO`，并且必须同时传入 `-AllowInsecureDemoPassword` 和确认值 `CONFIRM_DEMO_PASSWORD=I_UNDERSTAND_12345678_IS_DEMO_ONLY`。
- 账号 CSV 不包含密码字段；脚本内的 `12345678` 只是演示初始化临时值。
- 正式上线前必须运行 `scripts/ops/Reset-DemoAccountPasswords.ps1` 或由管理员手工改密，清除全部演示密码。

## 必需环境变量

后端运行环境：

```powershell
$env:APP_ENV='STAGING' # 或 DEMO；生产必须使用 PRODUCTION/PROD，并禁止演示密码
$env:MYSQL_HOST='<mysql-host>'
$env:MYSQL_PORT='<mysql-port>'
$env:MYSQL_DATABASE='<mysql-database>'
$env:MYSQL_USERNAME='<mysql-user>'
$env:MYSQL_PASSWORD='<mysql-password>'
$env:APP_BOOTSTRAP_ADMIN_USERNAME='<initial-boss-user>'
$env:APP_BOOTSTRAP_ADMIN_PASSWORD='<strong-initial-boss-password>'
```

账号初始化脚本：

```powershell
$env:API_BASE_URL='https://<server>:18443'
$env:BOSS_TOKEN='<login-token-from-boss-account>'
$env:ACCOUNTS_CSV='scripts/ops/accounts-cloud-demo.template.csv'
$env:APP_ENV='STAGING'
$env:CONFIRM_DEMO_PASSWORD='I_UNDERSTAND_12345678_IS_DEMO_ONLY'
```

## 后端 jar 与前端 dist 上传

推荐先在本地构建并确认版本：

```powershell
Push-Location backend
mvn -q test
mvn -q -DskipTests package
Pop-Location

Push-Location frontend-vue
npm run build
Pop-Location
```

如果使用不可变候选包流程：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-release-candidate.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/deploy-release-candidate.ps1 `
  -Server <server-host> `
  -User deploy `
  -CandidateDirectory <candidate-directory> `
  -RemoteRoot /opt/store-profit `
  -RestartUserService `
  -UserServiceName ai-profit-backend.service `
  -HealthUrl https://<server>:18443/api/health
```

如果演示服务器仍使用当前手工目录布局，需要手工上传：

```powershell
scp backend/target/store-profit-backend-0.1.0-SNAPSHOT.jar ubuntu@<server>:/tmp/store-profit-backend.jar
scp -r frontend-vue/dist ubuntu@<server>:/tmp/frontend-dist
```

云端替换时必须先备份：

```bash
sudo mkdir -p /opt/store-profit-system/backups
sudo cp -p /opt/store-profit-system/app/store-profit-backend.jar \
  /opt/store-profit-system/backups/store-profit-backend-before-demo-deploy-$(date +%Y%m%d-%H%M%S).jar
sudo install -o ubuntu -g ubuntu -m 0644 /tmp/store-profit-backend.jar \
  /opt/store-profit-system/app/store-profit-backend.jar
sudo systemctl restart store-profit-system.service
sudo systemctl is-active store-profit-system.service
```

前端 dist 的 Nginx 根目录以实际服务器配置为准；替换前同样备份当前目录或使用版本化目录加 symlink。

## 云端 MySQL 迁移

后端启动会执行 Flyway。部署后必须检查：

```powershell
Invoke-RestMethod "$env:API_BASE_URL/api/health"
```

确认响应中：

- `success=true`
- `data.status=UP`
- `data.environment=STAGING` 或 `DEMO`
- `data.databaseMigrationVersion` 等于仓库中 `backend/src/main/resources/db/migration/V*__*.sql` 的最新版本

如需空库预检，使用独立临时库执行仓库内已有 MySQL Flyway 验证脚本，不要复用生产库。

## 初始化 47 个账号

1. 确认云端已有模板中引用的门店 ID：`rg1, rg2, rg3, rg4, rg5, rg6, rg7, rg8, bw1, bw2, bw5, rx3`。如果门店不存在，先通过门店管理/API 创建门店，否则账号创建接口会返回 `STORE_SCOPE_INVALID`。
2. 使用 Boss 账号登录，取得 `BOSS_TOKEN`。
3. 执行初始化脚本：

```powershell
$env:API_BASE_URL='https://<server>:18443'
$env:BOSS_TOKEN='<boss-token>'
$env:ACCOUNTS_CSV='scripts/ops/accounts-cloud-demo.template.csv'
$env:APP_ENV='STAGING'
$env:CONFIRM_DEMO_PASSWORD='I_UNDERSTAND_12345678_IS_DEMO_ONLY'

powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ops/Provision-CloudDemoAccounts.ps1 `
  -AllowInsecureDemoPassword
```

如果演示域名仍使用自签名证书，追加 `-SkipCertificateCheck`；正式环境不得使用该开关。

脚本行为：

- 创建 CSV 中 47 个账号。
- 新账号统一临时密码为 `12345678`。
- 已存在账号默认跳过。
- 已存在账号只有显式传 `-ResetPassword` 才尝试重置；Boss 账号仍受后端自服务当前密码校验保护，不会被其它 Boss Token 静默重置。
- 输出创建/跳过/重置计数，不输出 `BOSS_TOKEN`。

## 验证接口和权限

执行：

```powershell
$env:DEMO_PASSWORD='12345678'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ops/Verify-CloudDemoDeployment.ps1 `
  -AllowInsecureDemoPassword
```

如果演示域名仍使用自签名证书，追加 `-SkipCertificateCheck`；正式环境不得使用该开关。

验证内容：

- `/api/health` 正常。
- Flyway 版本等于本地最新迁移。
- 47 个账号存在。
- `rg1` 至少 3 个员工账号存在且绑定 `rg1`。
- 员工账号登录后默认工作台为 `/employee`。
- 员工不能访问账号管理、财务、运营盘存、仓库管理接口。
- 督导默认 `/operations/inspection`，可读巡检，不能访问运营盘存入口。
- 运营默认 `/operations`，能访问运营盘存入口。
- `operation_log` 可读并包含账号相关审计记录。

## 正式上线前清除 12345678

正式上线前执行：

```powershell
$env:API_BASE_URL='https://<server>:18443'
$env:BOSS_TOKEN='<boss-token>'
$env:ACCOUNTS_CSV='scripts/ops/accounts-cloud-demo.template.csv'

powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ops/Reset-DemoAccountPasswords.ps1
```

输出文件会写入 `output/secure/demo-account-passwords-<timestamp>.csv`，该目录已被 `.gitignore` 忽略。文件包含一次性密码，只能短期留存在受控机器上；分发后应删除或转移到密码库。

Boss 账号密码有后端自保护：如果要通过脚本一起改 Boss 密码，需要额外设置 `CURRENT_BOSS_PASSWORD`；否则脚本会标记 `MANUAL_REQUIRED`，由 Boss 登录后手工改密。

上线检查项：

- `APP_ENV` 已切换到正式环境值，且没有演示密码确认变量。
- `CONFIRM_DEMO_PASSWORD` 已从服务器环境删除。
- 所有演示账号不再使用 `12345678`。
- `output/secure` 中的一次性密码文件已删除或移交到受控密码库。
- `/api/health` 正常，Flyway 版本正确。
- 抽查 Boss、财务、运营、督导、仓库、店长、员工账号登录和默认工作台。
