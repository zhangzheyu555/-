#!/bin/bash
# 一键启动整个系统：卫生识别服务(8000) + Spring 后端(8080)，就绪后自动打开浏览器
cd "$(dirname "$0")"

# 本机没有系统 Java/Maven，默认用 IDEA 自带 JBR 和 ~/tools/maven，可用环境变量覆盖
export JAVA_HOME="${JAVA_HOME:-/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home}"
MVN="${MVN:-$HOME/tools/maven/bin/mvn}"

# 卫生识别服务（已在运行则跳过）
if ! curl -s -o /dev/null http://127.0.0.1:8000/; then
  echo "启动卫生识别服务(8000)..."
  (cd inspection-service && ./start.sh) &
fi

# Spring 后端，本地用 H2 的 local profile（已在运行则跳过）
if ! curl -s -o /dev/null http://127.0.0.1:8080/; then
  echo "启动后端(8080)..."
  (cd backend && "$MVN" spring-boot:run -Dspring-boot.run.profiles=local -q) &
fi

echo "等待后端就绪..."
for i in $(seq 1 90); do
  curl -s -o /dev/null http://127.0.0.1:8080/ && break
  sleep 2
done

open http://localhost:8080/
echo "系统已打开：http://localhost:8080/ （按 Ctrl+C 停止全部服务）"
wait
