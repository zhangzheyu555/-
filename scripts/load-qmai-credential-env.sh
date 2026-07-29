#!/usr/bin/env bash

# 本机启动辅助：只读取企迈凭证加密密钥这一项，不执行 env 文件中的其他内容。
# 部署环境已经注入变量时保持原值，避免本机文件覆盖服务器或 CI 的密钥。
load_qmai_local_credential_key() {
  if [ -n "${QMAI_CREDENTIAL_ENCRYPTION_KEY:-}" ]; then
    return
  fi

  local config_root="${XDG_CONFIG_HOME:-$HOME/.config}"
  local env_file="${QMAI_LOCAL_ENV_FILE:-$config_root/completeproject/qmai.env}"
  if [ ! -r "$env_file" ]; then
    return
  fi

  local line
  local value=""
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      QMAI_CREDENTIAL_ENCRYPTION_KEY=*)
        value="${line#*=}"
        value="${value%$'\r'}"
        break
        ;;
    esac
  done < "$env_file"

  if [ "${#value}" -ge 2 ]; then
    if { [ "${value:0:1}" = '"' ] && [ "${value: -1}" = '"' ]; } \
        || { [ "${value:0:1}" = "'" ] && [ "${value: -1}" = "'" ]; }; then
      value="${value:1:${#value}-2}"
    fi
  fi

  if [ -n "$value" ]; then
    export QMAI_CREDENTIAL_ENCRYPTION_KEY="$value"
  fi
}

load_qmai_local_credential_key
unset -f load_qmai_local_credential_key
