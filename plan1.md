# AI 随身摄影师 — 实施计划（TRAE 大赛版）

> 目标：2-4 周内，把"实时人像拍照助手"打磨到可现场真机演示的完整闭环。
> 核心卖点：**实时指导**（本地规则引擎 + 云端通义 VL 增强 + 语音 + 可视化）。

## 目标与约束

| 项 | 说明 |
|----|------|
| 场景 | TRAE 大赛现场真机演示 |
| 时间 | 2-4 周 |
| 兜底 | 本地规则引擎始终运行，云端超时 2s 降级，用户无感知 |
| 模型 | 通义 VL（境内） |
| 演示风险 | 现场网络/限流 → 本地兜底覆盖 |

## 范围与优先级总览

### P0 — 演示核心闭环
1. **实时取景指导可视化**（视觉体验包）
2. **语音播报**（TTS）
3. **拍摄时机信号**（READY 强信号）
4. **云端通义 VL 实时增强 + 本地兜底**

### P1 — 打磨
5. **组合方向 / 触觉反馈 / 水平仪智能显隐 / 文案量化**
6. **复杂场景触发云端**

### P2 — 文案对齐
7. 调整 [展示页.html](展示页.html) 文案，"拍后评分"标注为规划中

### 明确后置（本次不做）
- 拍后评分与重拍建议（[PhotoScorer.kt](app/src/main/java/com/yuxiang/aiphoto/analysis/PhotoScorer.kt) 已有骨架，后置）
- 人体检测（ML Kit Pose Detection）
- 离线缓存、错误重试、降级策略全套

---

## P0-1 实时取景指导可视化（视觉体验包）

### 目标
把"仪表盘"变成"会动的 AI 摄影师"，让评委一眼看出是实时 AI。

### 任务

#### 1.1 修复"调整时指导消失"（体验硬伤）
- **问题**：[GuidanceStabilizer.kt:39-46](app/src/main/java/com/yuxiang/aiphoto/analysis/GuidanceStabilizer.kt#L39-L46) 不稳定帧时清空 `recommendationText` 和 `cameraAction`，导致用户按提示移动时箭头消失
- **方案**：不稳定帧保留上一次稳定的箭头/文字，仅降级 `captureReadiness`
- **改动**：`GuidanceStabilizer` 新增 `lastStableFrame` 缓存，不稳定时返回 `lastStableFrame.copy(captureReadiness = 降级后的值)`

#### 1.2 目标框可视化
- **目标**：画出主体应移动到的"目标位置"虚框，箭头连接当前框 → 目标框
- **方案**：
  - `CameraGuidanceModels.kt` 新增 `TargetZone`（目标矩形 + 置信度）
  - `GuidanceEngine` 根据 `subjectBox` + `sceneType` 计算理想位置（居中 + 顶部留白 12%）
  - `GuidanceOverlayView` 新增 `drawTargetZone()`：虚线框 + 连接箭头
- **改动文件**：[CameraGuidanceModels.kt](app/src/main/java/com/yuxiang/aiphoto/model/CameraGuidanceModels.kt)、[GuidanceEngine.kt](app/src/main/java/com/yuxiang/aiphoto/analysis/GuidanceEngine.kt)、[GuidanceOverlayView.kt](app/src/main/java/com/yuxiang/aiphoto/ui/GuidanceOverlayView.kt)

#### 1.3 呼吸/脉冲动效
- **目标**：READY 绿框脉冲、方向箭头呼吸、READY 时快门按钮放大
- **方案**：`GuidanceOverlayView` 用 `ValueAnimator` 驱动 alpha/scale 周期变化
- **改动**：[GuidanceOverlayView.kt](app/src/main/java/com/yuxiang/aiphoto/ui/GuidanceOverlayView.kt) 新增动画字段 + `onDraw` 读取动画值

#### 1.4 READY 强信号
- **目标**：READY 时快门按钮放大脉冲 + 边框转绿 + "现在可以拍" + 短震动
- **方案**：
  - `activity_main.xml` 快门按钮根据 readiness 变色/放大
  - `MainActivity` 监听 readiness 变化触发震动（`HapticFeedbackConstants.CONFIRM`）
- **改动**：[activity_main.xml](app/src/main/res/layout/activity_main.xml)、[MainActivity.kt](app/src/main/java/com/yuxiang/aiphoto/MainActivity.kt)

---

## P0-2 语音播报（TTS）

### 目标
"随身摄影师"定位的差异化核心。用户看被摄对象时也能收到指导。评委一听就懂"这是 AI 摄影师"。

### 任务

#### 2.1 TTS 引擎封装
- **方案**：新增 `audio/GuidanceSpeaker.kt`，封装 `TextToSpeech`
  - 节流：同一文案 3s 内不重复播报
  - 队列：新文案打断旧文案
  - 语音/静音开关（用户偏好）
- **改动**：新增 `app/src/main/java/com/yuxiang/aiphoto/audio/GuidanceSpeaker.kt`

#### 2.2 播报时机与文案
- **方案**：仅播报关键状态变化，不逐帧播报
  - readiness 升级到 READY → "可以拍了"
  - readiness 降级 → 不播报（避免噪音）
  - 方向提示变化 → "往左一点""下压手机""扶正"（节流 3s）
  - 光线异常 → "逆光，转向光源"
- **改动**：[MainViewModel.kt](app/src/main/java/com/yuxiang/aiphoto/ui/MainViewModel.kt) 监听状态变化触发播报

#### 2.3 播报开关
- **方案**：设置页新增"语音指导"开关，默认开
- **改动**：[UserPreferencesRepository.kt](app/src/main/java/com/yuxiang/aiphoto/prefs/UserPreferencesRepository.kt)、[dialog_cloud_settings.xml](app/src/main/res/layout/dialog_cloud_settings.xml)

---

## P0-3 拍摄时机信号

### 目标
构图、光线、水平达标时，给出不可错过的"现在可以拍"信号。

### 任务

#### 3.1 CaptureReadiness 已有骨架，需校准
- **现状**：[GuidanceEngine.kt:138-171](app/src/main/java/com/yuxiang/aiphoto/analysis/GuidanceEngine.kt#L138-L171) 已实现评分逻辑（构图 0.4 + 亮度 0.3 + 倾斜 0.2 + 缩放 0.1）
- **校准**：阈值 0.85/0.6 需真机调试确认，避免 READY 抖动
- **改动**：[GuidanceEngine.kt](app/src/main/java/com/yuxiang/aiphoto/analysis/GuidanceEngine.kt) 阈值常量化 + 真机调参

#### 3.2 READY 视觉强化
- 已在 P0-1.4 覆盖（快门放大 + 绿框 + 震动）

---

## P0-4 云端通义 VL 实时增强 + 本地兜底

### 目标
复杂场景下，云端多模态 LLM 给出比本地规则更智能的指导。本地始终兜底。

### 任务

#### 4.1 CloudGuidanceClient
- **方案**：新增 `review/CloudGuidanceClient.kt`
  - 帧采样：500ms 最多 1 次请求
  - 内容去重：相同场景指纹 2s 内不重复
  - 超时降级：2s 未返回则用本地结果
  - 图片压缩：上传 512x512 JPEG（~50KB）
- **改动**：新增文件

#### 4.2 复杂场景触发
- **方案**：新增 `analysis/SceneComplexityEvaluator.kt`
  - 简单场景（单人脸 + 光线正常 + 倾斜<2°）→ 纯本地
  - 复杂场景（多人/逆光/侧脸/遮挡）→ 触发云端
- **改动**：新增文件 + [FrameAnalyzer.kt](app/src/main/java/com/yuxiang/aiphoto/analysis/FrameAnalyzer.kt) 接入

#### 4.3 API 接入（通义 VL）
- **接口**：`POST /v1/camera/guidance`（见附录设计）
- **改动**：[CloudReviewRepository.kt](app/src/main/java/com/yuxiang/aiphoto/review/CloudReviewRepository.kt) 或新增 `CloudGuidanceRepository.kt`

#### 4.4 "AI 分析中"状态
- **方案**：云端请求中显示呼吸点动画，结果到了再更新
- **改动**：[CameraScreenState](app/src/main/java/com/yuxiang/aiphoto/ui/MainViewModel.kt) 新增 `cloudGuidanceState`，[GuidanceOverlayView.kt](app/src/main/java/com/yuxiang/aiphoto/ui/GuidanceOverlayView.kt) 绘制加载态

#### 4.5 渐进式展示
- **方案**：本地结果立即显示 → 云端结果到了再增强（不替换，叠加）
- **改动**：[MainViewModel.kt](app/src/main/java/com/yuxiang/aiphoto/ui/MainViewModel.kt)

---

## P1 打磨项

### 5.1 组合方向
- **问题**：[GuidanceEngine.kt:108-136](app/src/main/java/com/yuxiang/aiphoto/analysis/GuidanceEngine.kt#L108-L136) 只返回单轴方向
- **方案**：`DirectionHint` 改为支持组合（如 `MOVE_LEFT_DOWN`），或改为 `Set<DirectionHint>`
- **改动**：[CameraGuidanceModels.kt](app/src/main/java/com/yuxiang/aiphoto/model/CameraGuidanceModels.kt)、[GuidanceEngine.kt](app/src/main/java/com/yuxiang/aiphoto/analysis/GuidanceEngine.kt)、[GuidanceOverlayView.kt](app/src/main/java/com/yuxiang/aiphoto/ui/GuidanceOverlayView.kt)

### 5.2 触觉反馈
- READY → `HapticFeedbackConstants.CONFIRM`
- ALMOST → 轻震动提示"快好了"
- **改动**：[MainActivity.kt](app/src/main/java/com/yuxiang/aiphoto/MainActivity.kt)

### 5.3 水平仪智能显隐
- **问题**：[GuidanceOverlayView.kt:90-98](app/src/main/java/com/yuxiang/aiphoto/ui/GuidanceOverlayView.kt#L90-L98) 始终绘制
- **方案**：仅 `abs(horizonTiltDeg) > 2f` 时绘制
- **改动**：[GuidanceOverlayView.kt](app/src/main/java/com/yuxiang/aiphoto/ui/GuidanceOverlayView.kt)

### 5.4 文案量化
- "向左一点" → "向左移半身位"
- "再靠近一点" → "再靠近一步"
- **改动**：[GuidanceEngine.kt](app/src/main/java/com/yuxiang/aiphoto/analysis/GuidanceEngine.kt) 各 `*Recommendation` 方法

---

## P2 文案对齐

### 7. 展示页调整
- [展示页.html](展示页.html) 第 573 行"拍后评分与重拍建议"标注为"规划中"
- 避免演示时评委对照发现承诺未兑现

---

## 实施顺序

```
P0-1 视觉体验包 ──┐
P0-2 语音播报   ──┼──▶ 真机联调 ──▶ P1 打磨 ──▶ P2 文案对齐
P0-3 时机信号    ──┤
P0-4 云端增强    ──┘
```

- P0-1 / P0-2 / P0-3 可并行，P0-4 依赖 P0-1 的可视化基础
- 每个 P0 模块完成后立即真机验证
- P1 在 P0 全部跑通后进入

---

## 验收标准

1. **视觉体验包**：调整时箭头不消失 + 目标框可见 + READY 绿框脉冲 + 快门放大
2. **语音播报**：READY 时播报"可以拍了" + 方向变化播报（节流）+ 可开关
3. **拍摄时机**：达标时快门变绿 + 放大 + 震动 + "现在可以拍"
4. **云端增强**：复杂场景触发云端 + "AI 分析中"动画 + 超时 2s 本地兜底
5. **打磨项**：组合方向 + 触觉 + 水平仪智能显隐 + 文案量化
6. **演示可靠**：断网情况下本地指导完整可用

---

## 附录：云端 API 设计（参考）

### 实时拍摄指导 API

```http
POST /v1/camera/guidance
Content-Type: application/json

{
  "scene_type": "portrait",
  "image_base64": "...",        // 512x512 JPEG
  "face_count": 1,
  "face_positions": [...],
  "brightness_state": "balanced",
  "tilt_deg": 2.5,
  "confidence": 0.92,
  "request_id": "uuid-v4"
}
```

```json
{
  "scene_assessment": "good_composition",
  "guidance": {
    "action": "hold",
    "message": "构图良好，可以拍摄",
    "highlight_focus": true
  },
  "alternative_suggestions": [...],
  "estimated_score": 85
}
```

### 优化策略

| 策略 | 说明 |
|------|------|
| 帧采样 | 500ms 最多 1 次 |
| 内容去重 | 相同场景指纹 2s 内不重复 |
| 超时降级 | 2s 未返回用本地结果 |
| 图片压缩 | 512x512 JPEG ~50KB |
| 渐进展示 | 本地先显示，云端增强叠加 |

### 模型选型
- **通义 VL Plus**：实时指导（快、便宜）
- **通义 VL Max**：拍后点评（高质量，本次后置）
