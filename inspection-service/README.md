# 卫生复合识别服务

本文件夹是完整的卫生照片识别服务：训练好的 YOLO 模型（`models/floor_litter_seed_best.pt`，检测地面纸屑/污点，配合规则算法检测角落积灰）+ FastAPI 接口 + 巡检表 Excel 模板。门店系统页面的「现场拍照识别」和「生成Excel」都依赖它。

## Windows 部署（家里电脑照这个做）

1. 安装 Python：https://www.python.org/downloads/ 下载 3.12，安装时**勾选 "Add python.exe to PATH"**
2. 双击本文件夹里的 **`start.bat`**
   - 首次运行会在 `%LOCALAPPDATA%\AI-Profit-OS\inspection-venv` 创建短路径虚拟环境并安装依赖（下载约 1-2GB，等它跑完）
   - 虚拟环境放在仓库外，避免 PyTorch 在 Windows 深层目录中触发路径过长错误
   - 以后每次双击直接启动，几秒就好
3. 看到 `Uvicorn running on http://127.0.0.1:8000` 即成功，**这个窗口别关**
4. 再启动 Java 后端（backend 目录，需要 JDK21+Maven，没装 MySQL 就用：
   `mvn spring-boot:run -Dspring-boot.run.profiles=local`）
5. 浏览器打开 `http://127.0.0.1:8080/index.html` → 督导巡店 → 发起巡检 → 拖入照片

> 以上步骤可以直接把本 README 丢给 Codex/AI 助手让它代劳。

## Mac / Linux

```bash
./start.sh
```

## 接口

| 接口 | 说明 |
|---|---|
| `GET /health` | 服务与模型状态 |
| `POST /detect` | 上传图片（multipart `file`），返回红圈图 base64、合格/不合格、扣分建议 |
| `POST /export` | 传识别结果 JSON，返回《卫生复合当前汇总_*.xlsx》（用 `templates/` 里的巡检表模板排版） |

Java 后端通过 `INSPECTION_DETECT_URL` / `INSPECTION_EXPORT_URL` 环境变量指向本服务（默认 `http://127.0.0.1:8000/...`），详见 `docs/卫生识别接入说明.md`。

## 文件说明

- `api_server.py` — FastAPI 接口层
- `app.py` — 检测/画红圈/Excel 生成核心（也可 `streamlit run app.py` 打开原始审核工具页面）
- `models/floor_litter_seed_best.pt` — 本地训练的种子模型（类别 paper_scrap、stain）
- `yolo11n.pt` — 通用模型（种子模型缺失时的回退）
- `templates/万达二店巡检表6.23日.xlsx` — Excel 导出模板（优先用这份，其次找桌面同名文件）
- 运行后自动生成 `data/`（原图、红圈图、Excel 存档），已被 gitignore
