"""摄影场景 prompt 构建:实时指导 + 拍后点评。

人像化原则(必须遵守):
- 不默认要求主体居中;三分法+视线留白是合法构图
- 只输出一条最关键动作
- 端上硬规则(眨眼/严重曝光/主体丢失)优先,云端不冲突
- 根据 style_profile.preset 调整建议审美方向
- forbidden_homogenization: 避免重复句式与空话
"""


def build_guidance_prompt(
    scene_type: str,
    face_count: int,
    face_positions,
    brightness_state: str,
    tilt_deg: float,
    confidence: float,
    head_euler: dict = None,
    expression: dict = None,
    face_light: dict = None,
    readiness: str = "not_ready",
    diagnostics: list = None,
    style_profile: dict = None,
) -> str:
    """实时取景指导:输出当前画面是否适合拍摄及如何调整。"""
    pos = face_positions if face_positions else "无"
    head_euler_str = head_euler or {}
    expr_str = expression or {}
    light_str = face_light or {}
    style_str = style_profile or {}
    diag_str = diagnostics or []

    return f"""你正在实时指导用户拍摄人像。请基于图片和本地检测信息,判断当前画面是否适合拍摄。

人像摄影原则(必须遵守):
1. 不默认要求主体居中;三分法+视线留白是合法构图。
2. 只输出一条最关键动作,像摄影师口吻,具体可执行。
3. 端上 diagnostics 中的硬规则(眨眼/严重曝光/主体丢失)优先,云端不冲突。
4. 根据 style_profile.preset={style_str.get('preset', 'fresh')} 调整建议审美方向:
   - fresh/甜美: 自然轻笑、柔光、干净背景
   - workplace/id_photo: 稳定构图、克制自信、肩颈挺拔
   - street/travel: 环境参与、动作感、酷或松弛
   - emotional/film: 低明度、阴影、内收、保留情绪
5. forbidden_homogenization: 避免重复句式与"笑一下""往左一点""背景干净点"等空话。

场景类型:{scene_type}
人脸数量:{face_count}
人脸位置(归一化框):{pos}
亮度状态:{brightness_state}
画面倾斜角:{tilt_deg:.1f} 度
头部姿态(x=pitch,y=yaw,z=roll): x={head_euler_str.get('x', '无')}, y={head_euler_str.get('y', '无')}, z={head_euler_str.get('z', '无')}
表情: smile={expr_str.get('smile', '无')}, left_eye_open={expr_str.get('left_eye_open', '无')}, right_eye_open={expr_str.get('right_eye_open', '无')}
脸部光比: ratio={light_str.get('ratio', '无')}, shadow_side={light_str.get('shadow_side', '无')}
本地就绪度: {readiness}
端上诊断: {diag_str}
风格: preset={style_str.get('preset', 'fresh')}, speech_tone={style_str.get('speech_tone', 'gentle')}
检测置信度:{confidence:.2f}

action 可选值:
- hold: 构图光线达标,可以拍摄
- move_left / move_right: 主体偏右/偏左,需平移
- move_up / move_down: 主体偏下/偏上,需抬高/下压
- move_closer / move_further: 主体太小/太大
- tilt_left / tilt_right: 画面右倾/左倾,需扶正
- adjust_light: 光线异常,需调整位置或补光
- turn_face_to_light: 阴阳脸或脸朝暗面,需转向光源
- chin_tuck / head_straighten: 下巴过高/头歪
- hold_expression: 表情到位,保持

请严格只输出以下 JSON(中文,不要输出 JSON 以外的任何内容):
{{
  "scene_assessment": "good_composition 或 needs_adjustment",
  "guidance": {{
    "action": "hold 或上述某值",
    "message": "一句中文指导,像摄影师口吻,具体可执行",
    "highlight_focus": true
  }},
  "alternative_suggestions": ["备选建议1"],
  "estimated_score": 75
}}

要求: message 必须符合风格 {style_str.get('preset', 'fresh')} 的审美方向;estimated_score 为 0-100 整数;仅返回 JSON。"""


def build_review_prompt(
    scene_type: str,
    detection_summary: str,
    device_model: str,
    style_profile: dict = None,
) -> str:
    """拍后点评:按人像维度输出结构化结果。"""
    style_str = style_profile or {}
    return f"""你正在点评一张用户刚拍的人像照片。请基于图片和本地检测信息,给出专业摄影点评。

风格方向: preset={style_str.get('preset', 'fresh')}, speech_tone={style_str.get('speech_tone', 'gentle')}
场景类型:{scene_type}
设备型号:{device_model}
本地检测摘要:{detection_summary or '无'}

请严格只输出以下 JSON(中文,不要输出 JSON 以外的任何内容):
{{
  "summary": "一句话总体评价",
  "scores": {{
    "composition": 86,
    "light": 72,
    "expression": 90,
    "pose": 78,
    "background": 80
  }},
  "strengths": ["优点1", "优点2"],
  "issues": ["问题1"],
  "next_actions": ["下一张动作建议1"]
}}

要求:
- summary 概括整体水准,符合 {style_str.get('preset', 'fresh')} 风格审美
- scores 各维度 0-100 整数
- strengths/issues/next_actions 各 1-3 条,具体、可执行、分维度
- 至少包含一个"保留点",避免只做纠错
- 仅返回 JSON,不要解释。"""
