#!/bin/bash
# 卫生复合识别服务 Mac/Linux 启动脚本
cd "$(dirname "$0")"

if [ ! -x ".venv/bin/python" ]; then
  echo "首次运行：创建虚拟环境并安装依赖（下载约1-2GB）..."
  python3 -m venv .venv
  ./.venv/bin/python -m pip install --upgrade pip
  ./.venv/bin/python -m pip install -r requirements.txt || { echo "依赖安装失败"; exit 1; }
fi

echo "启动卫生复合识别服务：http://127.0.0.1:8000"
exec ./.venv/bin/python -m uvicorn api_server:api --host 127.0.0.1 --port 8000
