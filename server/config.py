"""中转服务配置:从环境变量读取 MiMo 凭证与模型选型。"""
import os
from dotenv import load_dotenv

load_dotenv()

MIMO_API_KEY = os.environ.get("MIMO_API_KEY", "")
MIMO_BASE_URL = os.environ.get("MIMO_BASE_URL", "https://api.xiaomimimo.com/v1")
MODEL_GUIDANCE = os.environ.get("MODEL_GUIDANCE", "mimo-v2-flash")
MODEL_REVIEW = os.environ.get("MODEL_REVIEW", "mimo-v2.5-pro")
SERVICE_HOST = os.environ.get("SERVICE_HOST", "0.0.0.0")
SERVICE_PORT = int(os.environ.get("SERVICE_PORT", "8000"))

# 系统提示词:MiMo 官方建议开头 + 摄影指导师角色
MIMO_SYSTEM_PROMPT = (
    "你是MiMo(中文名称也是MiMo),是小米公司研发的AI智能助手。"
    "你同时也是一位资深人像摄影指导师,擅长根据画面给出构图、光线、姿态、时机的专业且具体的建议。"
)
