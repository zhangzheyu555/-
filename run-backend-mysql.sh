#!/bin/bash
# 企迈链接销售汇总，物料损耗，营业额(最新codex分支+企迈板块)后端：MySQL 8(3307)，库 store_ops_qimai_final_test，端口 18081
export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$HOME/tools/maven/bin:$PATH"
export APP_ENV="${APP_ENV:-TEST}"
export SERVER_PORT="${SERVER_PORT:-18081}"
export MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
export MYSQL_PORT="${MYSQL_PORT:-3307}"
export MYSQL_DATABASE="${MYSQL_DATABASE:-store_ops_qimai_final_test}"
export MYSQL_USERNAME="${MYSQL_USERNAME:-storeapp}"
export MYSQL_SSL_MODE="${MYSQL_SSL_MODE:-DISABLED}"
: "${MYSQL_PASSWORD:?请先通过环境变量设置 MYSQL_PASSWORD}"
: "${APP_BOOTSTRAP_DEFAULT_USERS_PASSWORD:?请先通过环境变量设置 APP_BOOTSTRAP_DEFAULT_USERS_PASSWORD}"
: "${APP_BOOTSTRAP_STORE_MANAGER_PASSWORD:?请先通过环境变量设置 APP_BOOTSTRAP_STORE_MANAGER_PASSWORD}"

# 企迈凭证必须先用独立 AES 密钥加密后才能落库。macOS 本机优先从登录钥匙串读取，
# 其他环境仍由部署系统通过 QMAI_CREDENTIAL_ENCRYPTION_KEY 注入，密钥不写入仓库。
if [ -z "${QMAI_CREDENTIAL_ENCRYPTION_KEY:-}" ] && [ -x /usr/bin/security ]; then
  QMAI_CREDENTIAL_ENCRYPTION_KEY="$(
    /usr/bin/security find-generic-password \
      -s "AI-Profit-OS/QMAI_CREDENTIAL_ENCRYPTION_KEY" \
      -a "backend" \
      -w 2>/dev/null || true
  )"
fi
: "${QMAI_CREDENTIAL_ENCRYPTION_KEY:?请通过环境变量或 macOS 登录钥匙串配置企迈凭证加密密钥}"

export MYSQL_PASSWORD APP_BOOTSTRAP_DEFAULT_USERS_PASSWORD APP_BOOTSTRAP_STORE_MANAGER_PASSWORD
export QMAI_CREDENTIAL_ENCRYPTION_KEY
# 空库引导默认用户 + 演示数据，否则登不进
export APP_BOOTSTRAP_DEFAULT_USERS_ENABLED=true
export APP_BOOTSTRAP_STORE_MANAGER_ACCOUNTS_ENABLED=true
export APP_SEED_DEMO_ENABLED=true
cd "$(dirname "$0")/backend"
exec mvn -q -Dmaven.test.skip=true spring-boot:run 2>&1
