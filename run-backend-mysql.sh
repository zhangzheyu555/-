#!/bin/bash
# GitHub链接企迈(codex/deepseek-assistant + 企迈移植)后端：MySQL 8(3307)，库 store_ops_github_test，端口 18081
export JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$HOME/tools/maven/bin:$PATH"
export APP_ENV=TEST
export SERVER_PORT=18081
export MYSQL_HOST=127.0.0.1
export MYSQL_PORT=3307
export MYSQL_DATABASE=store_ops_github_test
export MYSQL_USERNAME=storeapp
export MYSQL_PASSWORD='StoreApp@2026'
export MYSQL_SSL_MODE=DISABLED
# 空库引导默认用户 + 演示数据，否则登不进
export APP_BOOTSTRAP_DEFAULT_USERS_ENABLED=true
export APP_BOOTSTRAP_DEFAULT_USERS_PASSWORD='Test@12345'
export APP_BOOTSTRAP_STORE_MANAGER_ACCOUNTS_ENABLED=true
export APP_BOOTSTRAP_STORE_MANAGER_PASSWORD='Mgr@12345'
export APP_SEED_DEMO_ENABLED=true
cd "$(dirname "$0")/backend"
exec mvn -q -Dmaven.test.skip=true spring-boot:run 2>&1
