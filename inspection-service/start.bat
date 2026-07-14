@echo off
rem 卫生复合识别服务 Windows 一键启动脚本
rem 首次运行会自动创建虚拟环境并安装依赖（需已安装 Python 3.9-3.12，安装时勾选 Add to PATH）
chcp 65001 >nul
cd /d "%~dp0"
set "VENV_DIR=%LOCALAPPDATA%\AI-Profit-OS\inspection-venv"

where python >nul 2>nul
if errorlevel 1 (
  echo [错误] 未找到 Python，请先到 https://www.python.org/downloads/ 安装（勾选 Add python.exe to PATH）
  pause
  exit /b 1
)

if not exist "%VENV_DIR%\Scripts\python.exe" (
  echo 首次运行：创建虚拟环境并安装依赖（下载约1-2GB，请耐心等待）...
  if not exist "%LOCALAPPDATA%\AI-Profit-OS" mkdir "%LOCALAPPDATA%\AI-Profit-OS"
  python -m venv "%VENV_DIR%"
  "%VENV_DIR%\Scripts\python.exe" -m pip install --upgrade pip
  "%VENV_DIR%\Scripts\python.exe" -m pip install -r requirements.txt
  if errorlevel 1 (
    echo [错误] 依赖安装失败，请检查网络后重新运行本脚本
    pause
    exit /b 1
  )
)

echo 启动卫生复合识别服务：http://127.0.0.1:8000
"%VENV_DIR%\Scripts\python.exe" -m uvicorn api_server:api --host 127.0.0.1 --port 8000
pause
