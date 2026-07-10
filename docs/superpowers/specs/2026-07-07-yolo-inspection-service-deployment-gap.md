# YOLO 巡检识别服务部署缺口与解决步骤

日期：2026-07-07

## 结论

第三个待处理问题是“YOLO 巡检识别服务未部署”。当前后端和页面已经接到 `/api/inspections/detect`，但云服务器没有可用的 Python/FastAPI YOLO 服务，因此接口稳定返回 502。

本次不应使用模拟返回冒充识别结果。要恢复真实识别，必须拿到原训练产物后再部署。

## 已验证现象

- 云端入口：`https://175.178.89.183:18443/index.html`
- 检测接口：`POST https://175.178.89.183:18443/api/inspections/detect`
- 当前返回：

```json
{
  "success": false,
  "message": "卫生识别服务不可用，请确认识别服务已启动",
  "code": "INSPECTION_SERVICE_UNAVAILABLE",
  "data": null
}
```

后端代码路径：

- `InspectionController` 接收 `/api/inspections/detect`
- `InspectionService` 将图片转发到 `app.inspection.detect-url`
- 默认检测地址：`http://127.0.0.1:8000/detect`
- 默认导出地址：`http://127.0.0.1:8000/export`

## 根因

仓库文档要求识别服务位于：

```text
C:\Users\34706\Documents\Codex\2026-07-06\wo\outputs\kitchen_yolo_review_app
```

但本机未找到该目录，也未找到关键产物：

- `kitchen_yolo_review_app`
- `api_server.py`
- `models/floor_litter_seed_best.pt`

因此当前无法把“真实 YOLO 服务”上传到云服务器。

## 必需产物

继续部署前需要补齐以下文件或目录：

```text
kitchen_yolo_review_app/
  api_server.py                 # FastAPI app，需暴露 api 变量
  requirements.txt              # 或 pyproject.toml
  models/
    floor_litter_seed_best.pt    # 训练后的 YOLO 模型
  app.py                        # 如 /export 复用该文件，则必须包含
  *.xlsx                        # 如 Excel 导出依赖模板，也必须包含
```

接口契约：

- `POST /detect`：接收 multipart `file`
- `POST /export`：接收 JSON，返回 xlsx，并设置 `X-Export-Filename`
- 服务必须绑定 `127.0.0.1:8000`，只给 Java 后端本机访问，不开放公网端口

## 云端目标结构

```text
/opt/store-profit-yolo/
  app/
    api_server.py
    requirements.txt
    models/floor_litter_seed_best.pt
  .venv/
```

systemd 服务：

```ini
[Unit]
Description=Store Profit YOLO Inspection Service
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/store-profit-yolo/app
ExecStart=/opt/store-profit-yolo/.venv/bin/uvicorn api_server:api --host 127.0.0.1 --port 8000
Restart=always
RestartSec=5
User=root

[Install]
WantedBy=multi-user.target
```

后端环境变量：

```text
INSPECTION_DETECT_URL=http://127.0.0.1:8000/detect
INSPECTION_EXPORT_URL=http://127.0.0.1:8000/export
INSPECTION_TIMEOUT=60s
```

## 部署步骤

拿到 `kitchen_yolo_review_app` 后：

1. 上传目录到云服务器 `/opt/store-profit-yolo/app`
2. 创建虚拟环境：

```bash
python3 -m venv /opt/store-profit-yolo/.venv
/opt/store-profit-yolo/.venv/bin/pip install --upgrade pip
/opt/store-profit-yolo/.venv/bin/pip install -r /opt/store-profit-yolo/app/requirements.txt
```

3. 创建并启用 systemd 服务：

```bash
systemctl daemon-reload
systemctl enable --now store-profit-yolo.service
systemctl status store-profit-yolo.service --no-pager
```

4. 重启 Java 后端：

```bash
systemctl restart store-profit-system.service
```

## 验证步骤

服务器本机验证：

```bash
curl -s -F "file=@/path/to/sample.jpg" http://127.0.0.1:8000/detect
```

Java 后端验证：

```bash
curl -k -s -F "file=@/path/to/sample.jpg" https://175.178.89.183:18443/api/inspections/detect
```

浏览器验证：

1. 打开 `https://175.178.89.183:18443/index.html`
2. 进入督导巡店的现场拍照识别入口
3. 上传一张测试图片
4. 确认页面展示识别结果和红圈图
5. 点击生成 Excel，确认能下载 xlsx

## 回滚步骤

如识别服务导致资源占用过高或启动失败：

```bash
systemctl disable --now store-profit-yolo.service
systemctl restart store-profit-system.service
```

后端会继续返回明确的 `INSPECTION_SERVICE_UNAVAILABLE`，不会影响老板权限、DeepSeek、仓库 MVP、数据导入等主流程。
