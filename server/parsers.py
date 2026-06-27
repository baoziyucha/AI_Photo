"""解析 MiMo 自由文本输出为结构化 JSON,容错处理。"""
import json
import re


def extract_json(text: str):
    """从模型输出中提取 JSON 对象,失败返回 None。"""
    if not text:
        return None
    text = text.strip()

    # 1. 直接解析
    try:
        return json.loads(text)
    except Exception:
        pass

    # 2. 提取 ```json ... ``` 代码块
    m = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.DOTALL)
    if m:
        try:
            return json.loads(m.group(1))
        except Exception:
            pass

    # 3. 提取第一个 { ... } 块(贪婪到最后一个 })
    m = re.search(r"\{.*\}", text, re.DOTALL)
    if m:
        try:
            return json.loads(m.group(0))
        except Exception:
            pass

    return None
