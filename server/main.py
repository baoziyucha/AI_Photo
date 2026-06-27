"""AI Photo MiMo 中转服务入口。

对接 App 现有两个端点:
- POST /v1/camera/guidance  实时取景指导(对应 CloudGuidanceClient)
- POST /v1/photo/review    拍后照片点评(对应 CloudReviewRepository)
"""
import base64
import json
import logging
from typing import Dict, List, Optional

from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from pydantic import BaseModel

from config import MODEL_GUIDANCE, MODEL_REVIEW, SERVICE_HOST, SERVICE_PORT
from mimo_client import chat_with_image
from prompts import build_guidance_prompt, build_review_prompt
from parsers import extract_json

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("mimo-proxy")

app = FastAPI(title="AI Photo MiMo Proxy", version="0.1.0")


@app.get("/health")
def health():
    return {"ok": True}


# ============ 实时取景指导(对应 CloudGuidanceClient)============
class GuidanceRequest(BaseModel):
    scene_type: str = "daily_generic"
    image_base64: str = ""
    face_count: int = 0
    face_positions: Optional[list] = None
    brightness_state: str = "balanced"
    tilt_deg: float = 0.0
    confidence: float = 0.0
    head_euler: Optional[Dict] = None
    expression: Optional[Dict] = None
    face_light: Optional[Dict] = None
    readiness: Optional[str] = None
    diagnostics: Optional[List] = None
    style_profile: Optional[Dict] = None
    request_id: Optional[str] = None


@app.post("/v1/camera/guidance")
def camera_guidance(req: GuidanceRequest):
    if not req.image_base64:
        raise HTTPException(400, "image_base64 为空")
    try:
        image_bytes = base64.b64decode(req.image_base64)
    except Exception:
        raise HTTPException(400, "image_base64 解码失败")

    prompt = build_guidance_prompt(
        req.scene_type, req.face_count, req.face_positions,
        req.brightness_state, req.tilt_deg, req.confidence,
        head_euler=req.head_euler,
        expression=req.expression,
        face_light=req.face_light,
        readiness=req.readiness or "not_ready",
        diagnostics=req.diagnostics,
        style_profile=req.style_profile,
    )
    try:
        raw = chat_with_image(
            MODEL_GUIDANCE, prompt, image_bytes=image_bytes,
            max_tokens=2048, temperature=0.3,
        )
    except Exception as e:
        logger.exception("guidance mimo call failed")
        raise HTTPException(502, f"MiMo 调用失败: {e}")

    logger.info("guidance raw: %s", raw)
    parsed = extract_json(raw)
    if not parsed:
        raise HTTPException(502, "MiMo 输出解析失败")
    return parsed


# ============ 拍后照片点评(对应 CloudReviewRepository)============
@app.post("/v1/photo/review")
async def photo_review(
    file: UploadFile = File(...),
    sceneType: str = Form("daily_generic"),
    detectionSummary: str = Form(""),
    deviceModel: str = Form(""),
    styleProfile: str = Form("{}"),
):
    image_bytes = await file.read()
    if not image_bytes:
        raise HTTPException(400, "图片为空")

    try:
        style_profile = json.loads(styleProfile) if styleProfile else None
    except Exception:
        style_profile = None
    prompt = build_review_prompt(sceneType, detectionSummary, deviceModel, style_profile=style_profile)
    try:
        raw = chat_with_image(
            MODEL_REVIEW, prompt, image_bytes=image_bytes,
            max_tokens=1024, temperature=0.4,
        )
    except Exception as e:
        logger.exception("review mimo call failed")
        raise HTTPException(502, f"MiMo 调用失败: {e}")

    parsed = extract_json(raw)
    if not parsed:
        raise HTTPException(502, "MiMo 输出解析失败")
    return parsed


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=SERVICE_HOST, port=SERVICE_PORT, reload=False)
