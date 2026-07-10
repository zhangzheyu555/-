from __future__ import annotations

import base64
import tempfile
import urllib.parse
from datetime import datetime
from functools import lru_cache
from io import BytesIO
from pathlib import Path
from typing import List

from fastapi import Body, FastAPI, File, HTTPException, Query, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from PIL import Image
from ultralytics import YOLO

import app as detector


api = FastAPI(title="卫生复合识别接口", version="1.0.0")

api.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


@lru_cache(maxsize=2)
def get_model(model_path: str) -> YOLO:
    return YOLO(model_path)


# 标注图最长边上限：原图分辨率的 PNG base64 可达几十 MB，会撑爆 Spring 代理端读取；
# 旧版页面入库前也只保留最长边 900，返回 1280 已绰绰有余
ANNOTATED_MAX_SIDE = 1280


def image_to_data_url(image: Image.Image) -> str:
    longest = max(image.size)
    if longest > ANNOTATED_MAX_SIDE:
        scale = ANNOTATED_MAX_SIDE / longest
        image = image.resize(
            (round(image.width * scale), round(image.height * scale)), Image.LANCZOS
        )
    buffer = BytesIO()
    image.convert("RGB").save(buffer, format="JPEG", quality=85)
    encoded = base64.b64encode(buffer.getvalue()).decode("ascii")
    return f"data:image/jpeg;base64,{encoded}"


def default_deduction(detections: List[dict], auto_status: str) -> dict:
    if not detections:
        return {
            "deduction_project": "",
            "deduction_content": "",
            "deduction_score": "",
        }

    class_names = {str(item.get("class_name", "")).lower() for item in detections}
    has_floor_litter = any(item.get("on_floor") for item in detections)
    has_corner_dust = "corner_dust" in class_names

    if has_floor_litter and has_corner_dust:
        content = "地面有纸屑/垃圾/污点，角落有积灰，需及时清理干净"
        score = -2
    elif has_corner_dust:
        content = "角落/灯带/边角有积灰污点，需及时清理干净"
        score = -1
    else:
        content = "地面有纸屑/垃圾/污点，需及时清理干净"
        score = -1

    return {
        "deduction_project": "店铺内部",
        "deduction_content": content or auto_status,
        "deduction_score": score,
    }


def save_data_url(data_url: str | None, target: Path) -> Path | None:
    if not data_url:
        return None
    try:
        encoded = data_url.split(",", 1)[1] if "," in data_url else data_url
        target.write_bytes(base64.b64decode(encoded))
        return target
    except Exception:
        return None


@api.get("/")
def root() -> dict:
    return {
        "name": "卫生复合识别接口",
        "docs": "/docs",
        "detect": "POST /detect",
        "export": "POST /export",
    }


@api.post("/export")
def export_excel(payload: dict = Body(...)) -> Response:
    rows_in = payload.get("rows") or []
    if not isinstance(rows_in, list) or not rows_in:
        raise HTTPException(status_code=400, detail="没有可导出的识别结果")

    store_name = str(payload.get("store_name") or "").strip() or detector.DEFAULT_STORE_NAME
    template_path = detector.default_template_path()
    now = datetime.now().isoformat(timespec="seconds")

    try:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            rows = []
            for index, item in enumerate(rows_in):
                score = detector.parse_deduction_score(item.get("deduction_score"))
                rows.append(
                    {
                        "image_id": str(item.get("image_id") or f"photo_{index + 1}"),
                        "filename": str(item.get("filename") or ""),
                        "human_label": str(item.get("human_label") or "待审核"),
                        "auto_status": str(item.get("auto_status") or ""),
                        "deduction_project": str(item.get("deduction_project") or ""),
                        "deduction_content": str(item.get("deduction_content") or ""),
                        "deduction_score": detector.format_score_value(score) if score is not None else "",
                        "detection_count": item.get("detection_count", ""),
                        "detection_summary": str(item.get("detection_summary") or ""),
                        "timestamp": str(item.get("timestamp") or now),
                        "original_path": save_data_url(item.get("original_image"), temp / f"orig_{index:03d}.jpg"),
                        "annotated_path": save_data_url(item.get("annotated_image"), temp / f"anno_{index:03d}.png"),
                    }
                )
            data, output_path, _ = detector.write_excel_rows(
                rows,
                detector.EXCEL_REPORT_DIR,
                "卫生复合当前汇总",
                store_name,
                template_path,
            )
    except HTTPException:
        raise
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Excel 生成失败：{exc}") from exc

    quoted = urllib.parse.quote(output_path.name)
    return Response(
        content=data,
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        headers={
            "Content-Disposition": f"attachment; filename*=UTF-8''{quoted}",
            "X-Export-Filename": quoted,
        },
    )


@api.get("/health")
def health() -> dict:
    return {
        "ok": True,
        "model_path": detector.DEFAULT_MODEL_PATH,
        "has_trained_model": detector.HAS_TRAINED_MODEL,
    }


@api.post("/detect")
async def detect(
    file: UploadFile = File(...),
    model_path: str = Query(detector.DEFAULT_MODEL_PATH, description="YOLO .pt 模型路径"),
    conf: float = Query(detector.DEFAULT_CONFIDENCE, ge=0.01, le=0.90, description="置信度"),
    imgsz: int = Query(detector.DEFAULT_IMAGE_SIZE, description="推理图片尺寸"),
    target_classes: str = Query(detector.DEFAULT_TARGET_CLASSES, description="英文类别，逗号分隔"),
    only_floor: bool = Query(True, description="YOLO 只保留地面目标"),
    floor_start_percent: int = Query(detector.DEFAULT_FLOOR_START_PERCENT, ge=0, le=80),
    detect_small_litter: bool = Query(not detector.HAS_TRAINED_MODEL, description="补充检测小碎屑"),
    litter_sensitivity: str = Query("中", pattern="^(低|中|高)$"),
    detect_corner_dust: bool = Query(True, description="大圈标记角落积灰"),
    corner_dust_sensitivity: str = Query("中", pattern="^(低|中|高)$"),
) -> dict:
    image_bytes = await file.read()
    if not image_bytes:
        raise HTTPException(status_code=400, detail="请上传图片文件")

    try:
        image = Image.open(BytesIO(image_bytes)).convert("RGB")
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"图片读取失败：{exc}") from exc

    try:
        model = get_model(model_path)
        annotated, detections = detector.detect_and_annotate(
            model=model,
            image=image,
            conf=conf,
            imgsz=imgsz,
            target_classes=detector.normalize_class_list(target_classes),
            only_floor=only_floor,
            floor_start_ratio=floor_start_percent / 100,
            detect_small_litter=detect_small_litter,
            litter_sensitivity=litter_sensitivity,
            detect_corner_dust_enabled=detect_corner_dust,
            corner_dust_sensitivity=corner_dust_sensitivity,
        )
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"识别失败：{exc}") from exc

    auto_status = detector.auto_status_from_detections(detections, only_floor)
    deduction = default_deduction(detections, auto_status)

    return {
        "image_id": detector.image_digest(image_bytes),
        "filename": file.filename,
        "passed": len(detections) == 0,
        "review_status": "合格" if not detections else "不合格",
        "auto_status": auto_status,
        "detection_count": len(detections),
        "detections": detections,
        "detection_summary": detector.summarize_detection_list(detections),
        "annotated_image": image_to_data_url(annotated),
        **deduction,
    }
