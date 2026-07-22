FROM python:3.11-slim

WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1
COPY requirements.txt ./
# 服务不需要图形桌面。headless 变体与项目使用的 cv2 API 兼容，并避免
# 在开发容器中下载 libGL 等仅桌面渲染需要的系统库。
RUN pip install --no-cache-dir torch==2.5.1 torchvision==0.20.1 \
    && sed -i 's/^opencv-python==/opencv-python-headless==/' requirements.txt \
    && pip install --no-cache-dir -r requirements.txt
# ultralytics 的可选依赖会再次安装带 GUI 的 opencv-python；最终镜像强制
# 保留 headless 变体，避免在服务端引入 libGL 图形运行时。
RUN pip uninstall -y opencv-python \
    && pip install --no-cache-dir --force-reinstall opencv-python-headless==4.10.0.84
COPY . .
EXPOSE 8000
CMD ["python", "-m", "uvicorn", "api_server:api", "--host", "0.0.0.0", "--port", "8000"]
