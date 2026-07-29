# 企迈凭证加密密钥运维

本文用于修复 `QMAI_CREDENTIAL_KEY_MISSING`。它只描述操作流程，不包含任何真实密钥。
执行前请确认生产数据库中尚无要保留的企迈密文；若已有密文，必须继续使用原来的同一把
主密钥，绝不能重新生成替换。

## 安全边界

- `QMAI_OPEN_KEY` 是企迈签名密钥；`QMAI_CREDENTIAL_ENCRYPTION_KEY` 是本系统 AES 主密钥，
  两者不可互换。
- 主密钥只能在 Linux 服务器的 root 受限文件和受控密码库中保存；不得写入 Git、前端、
  `application.yml`、发布制品、CI 日志或聊天记录。
- 主密钥必须保持不变。更换后，旧的 `enc:v1:` 数据将无法解密。
- 当前发布脚本仅更新 Jar 与前端静态文件，不复制或覆盖服务器的 `.env`，因此不会覆盖该密钥。

## 首次配置（需要一次后端短暂重启）

以下步骤只应由拥有服务器 root 权限的人员在维护窗口执行；不要把命令输出或密钥内容
发送到聊天。

1. 确认实际运行的项目目录为 `/root/store-profit`，并确认其 `docker-compose.yml` 已包含
   `QMAI_CREDENTIAL_ENCRYPTION_KEY` 到 `backend.environment` 的转发。
2. 在服务器本地生成 32 字节随机值，直接写入仅 root 可读的临时文件，不打印到终端：

   ```bash
   install -d -m 0700 /root/store-profit/secrets
   umask 077
   openssl rand -base64 32 > /root/store-profit/secrets/qmai-credential-key.b64
   ```

3. 将该文件内容保存到公司受控密码库或离线加密介质。该恢复副本必须与服务器分开保管，
   且只允许必要管理员读取。不要通过即时通讯工具、邮件明文或 Git 保存。
4. 将密钥写入服务器真实环境文件（该文件必须是 Git 忽略文件），不在终端输出密钥：

   ```bash
   key_file=/root/store-profit/secrets/qmai-credential-key.b64
   env_file=/root/store-profit/.env
   grep -q '^QMAI_CREDENTIAL_ENCRYPTION_KEY=' "$env_file" \
     && sed -i '/^QMAI_CREDENTIAL_ENCRYPTION_KEY=/d' "$env_file"
   printf 'QMAI_CREDENTIAL_ENCRYPTION_KEY=%s\n' "$(cat "$key_file")" >> "$env_file"
   chmod 0600 "$env_file" "$key_file"
   chown root:root "$env_file" "$key_file"
   ```

5. 在不查看密钥内容的前提下，重建后端容器以载入环境变量：

   ```bash
   cd /root/store-profit
   docker compose up -d --no-deps backend
   ```

## 本机与服务器同时使用

- macOS 本机通过 `run-backend-mysql.sh` 启动时，依次使用启动环境变量、登录钥匙串
  `AI-Profit-OS/QMAI_CREDENTIAL_ENCRYPTION_KEY`，最后才读取 `${QMAI_LOCAL_ENV_FILE}`；
  未指定该变量时默认读取 `~/.config/completeproject/qmai.env`。文件加载器只读取
  `QMAI_CREDENTIAL_ENCRYPTION_KEY`，不会执行文件中的其他内容。
- 服务器使用 `deploy/docker-compose.production.yml` 时，继续由 `ENV_FILE` 指向服务器自己的
  受限环境文件。生产部署不会读取或依赖开发电脑上的文件。
- 两端连接同一个数据库或使用从同一数据库复制出的企迈密文时，必须配置同一把主密钥。
  两端数据库完全独立且没有复制密文时，应各自使用独立主密钥。
- 已由容器、CI 或启动环境注入的 `QMAI_CREDENTIAL_ENCRYPTION_KEY` 优先级最高，本机加载器
  不会覆盖它。

## 无密钥泄露验证

后端健康后，仅验证变量是否存在，不显示值：

```bash
cd /root/store-profit
docker compose exec -T backend sh -c '[ -n "${QMAI_CREDENTIAL_ENCRYPTION_KEY:-}" ]' \
  && echo 'QMAI credential encryption key: present'
```

随后由老板在“平台配置 → 企迈 → 配置连接”保存一次真实配置。数据库检查只返回状态，
不读取任何凭证内容：

```sql
select
  sum(open_id <> '' and open_id not like 'enc:v1:%') as open_id_not_ciphertext,
  sum(grant_code <> '' and grant_code not like 'enc:v1:%') as grant_code_not_ciphertext,
  sum(open_key <> '' and open_key not like 'enc:v1:%') as open_key_not_ciphertext
from qmai_platform_config;
```

三个结果均为 `0` 才表示已有非空凭证均已以密文保存。重启后端后再次打开企迈配置页；
若状态仍显示已配置，说明同一密钥可以继续解密历史凭证。

## 已有密文的恢复

若服务器已存在 `enc:v1:` 数据：

1. 不要生成新密钥，也不要重置配置表。
2. 从受控密码库恢复原始 Base64 主密钥到 `/root/store-profit/secrets/qmai-credential-key.b64`，
   权限保持 `0600`、属主保持 `root:root`。
3. 按“首次配置”的第 4、5 步重新注入并重启后端。

若原始密钥与独立恢复副本都丢失，旧密文不可恢复；只能由授权管理员重新录入企迈凭证，
不能尝试绕过或降级加密。
