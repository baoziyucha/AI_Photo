"""MiMo 多模态调用封装(OpenAI 兼容协议)。

中转服务只做转发:App 请求 → 拼 prompt+图 → 调 MiMo → 返回文本。
API Key 仅存于服务端环境变量,不暴露给 App。
"""
import base64
import logging
from typing import Optional

from openai import OpenAI

from config import MIMO_API_KEY, MIMO_BASE_URL, MIMO_SYSTEM_PROMPT

logger = logging.getLogger("mimo-proxy")

_client: Optional[OpenAI] = None


def get_client() -> OpenAI:
    """惰性创建 OpenAI 兼容客户端。"""
    global _client
    if not MIMO_API_KEY:
        raise RuntimeError("MIMO_API_KEY 未配置,请在 server/.env 中设置")
    if _client is None:
        # max_retries=0:429/超时直接抛出,不自动重试(避免加重限流,让 App 走本地兜底)
        _client = OpenAI(api_key=MIMO_API_KEY, base_url=MIMO_BASE_URL, max_retries=0)
    return _client


def chat_with_image(
    model: str,
    user_text: str,
    image_bytes: Optional[bytes] = None,
    image_media_type: str = "image/jpeg",
    max_tokens: int = 1024,
    temperature: float = 0.4,
) -> str:
    """调用 MiMo 多模态对话,返回模型文本内容。

    prompt 中已强约束"只输出 JSON",未使用 response_format 以避免不同模型兼容性差异,
    由 parsers.extract_json 做容错解析。
    """
    client = get_client()
    content = []
    if image_bytes:
        b64 = base64.b64encode(image_bytes).decode("utf-8")
        data_url = f"data:{image_media_type};base64,{b64}"
        content.append({"type": "image_url", "image_url": {"url": data_url}})
    content.append({"type": "text", "text": user_text})

    resp = client.chat.completions.create(
        model=model,
        messages=[
            {"role": "system", "content": MIMO_SYSTEM_PROMPT},
            {"role": "user", "content": content},
        ],
        max_completion_tokens=max_tokens,
        temperature=temperature,
    )
    msg = resp.choices[0].message
    text = msg.content or ""
    if not text:
        # thinking 模式下 content 可能为空,实际输出可能在 reasoning_content
        rc = getattr(msg, "reasoning_content", None)
        logger.warning("content empty, reasoning_content len=%s", len(rc) if rc else 0)
        text = rc or ""
    return text
