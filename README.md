# AI 随身摄影师

> 安卓原生 `Kotlin + CameraX` 实时拍照助手 —— 本地 AI 视觉规则引擎驱动，取景框内实时构图指导、自动调参、拍后评分与云端点评。

## 当前能力

### 实时取景指导
- **场景识别**：人像、自拍、宠物/孩童、日常通用，自动切换指导策略
- **主体检测**：ML Kit 人脸检测 + 自定义亮度显著性回退（LumaSubjectDetector），无脸场景也能追踪主体
- **多维度分析**：亮度评估（正常/偏暗/逆光/过曝）、光源方向估计、画面倾斜检测、脸部朝向估计
- **实时指导文案**：根据场景 + 主体位置 + 光线 + 倾斜 + 脸部角度，生成具体可执行的中文建议（靠近/后退/左右偏移/抬高压低/扶正/转向光源）
- **方向箭头可视化**：取景框内叠加方向箭头（上下左右 + 旋转），呼吸动效，一目了然
- **目标框引导**：虚线框标示主体应移动到的理想位置，当前主体到目标框的连接虚线实时刷新
- **拍摄时机信号**：`NOT_READY → ALMOST_READY → READY` 三级评分，READY 时快门放大脉冲 + 绿框呼吸 + 震动反馈
- **指导去抖稳定**：GuidanceStabilizer 连续 N 帧一致才输出稳定指导，避免箭头/文案闪烁；不稳定帧保留上一帧的稳定指导，确保用户调整时方向指引不消失

### 自动调参（AI Assist）
- 主体跟随对焦/测光（AF/AE metering point 实时更新）
- 曝光补偿自动小步调整（逆光/偏暗 +1，过曝 -1）
- 手电筒建议（逆光/低光场景提示开补光）
- 可一键开关（AI Assist Switch）

### 拍照与存储
- 静态拍照保存到系统相册 `Pictures/AiPhoto`
- 前后摄切换

### 拍后评分（PhotoScorer）
- 构图分（权重 0.4）：主体大小、居中程度、是否贴边
- 亮度分（权重 0.3）：正常/偏暗/逆光/过曝
- 倾斜分（权重 0.2）：水平偏差
- 脸部角度分（权重 0.1）：脸是否正对镜头
- 综合评分 < 60 分或存在严重问题时建议重拍，输出具体重拍理由

### 云端点评
- 开关与服务地址可配置（SharedPreferences 持久化）
- `POST /v1/photo/review`，multipart/form-data 上传压缩后照片 + 场景类型 + 检测摘要 + 设备型号
- 响应展示：总结、优点、问题、建议

## 技术栈

| 分类 | 技术选型 |
|------|----------|
| 语言 | Kotlin 1.9.24 |
| JVM 目标 | Java 17 |
| 构建 | Gradle 8.7，Android Gradle Plugin 8.6.1 |
| Android 基础 | minSdk 29，targetSdk 35，AndroidX，Material 3，ConstraintLayout，ViewBinding |
| 架构 | Activity + AndroidViewModel，StateFlow，Kotlin Coroutines |
| 相机 | CameraX 1.4.1（Preview / ImageCapture / ImageAnalysis）+ Camera2Interop |
| 本地 AI | ML Kit Face Detection 16.1.7 + 自定义亮度/逆光/倾斜/主体显著性/光源方向/脸部朝向规则引擎 |
| 传感器 | Rotation Vector Sensor → 设备倾斜角（DeviceTiltMonitor） |
| 网络 | OkHttp 4.12.0，Gson 2.11.0 |
| 存储 | SharedPreferences（设置持久化），MediaStore（照片存储） |
| 测试 | JUnit 4，Google Truth，Espresso，AndroidX Test，kotlinx-coroutines-test |

## 构建与运行

```bash
# 编译 debug APK
./gradlew assembleDebug

# 运行单元测试
./gradlew testDebugUnitTest

# 安装到设备/模拟器
./gradlew installDebug
```

默认 Android SDK 路径：`/Users/yuxiang/Library/Android/sdk`（在 `local.properties` 中配置）。

### 环境要求

- JDK 17+
- Android SDK Platform 35
- Android Build Tools 35.0.0+
- 已连接的 Android 设备（API 29+）或模拟器

## 测试环境

- **主要测试设备**：OPPO Find X8
- **系统版本**：Android 15
- **相机特性**：双主摄（50MP 广角 + 50MP 超广角）、哈苏影像系统

## 代码结构

```
app/src/main/java/com/yuxiang/aiphoto/
├── MainActivity.kt              # 唯一 Activity：权限、相机绑定、状态渲染、用户交互
├── camera/
│   └── AiCameraManager.kt       # CameraX 生命周期绑定、预览/拍照/分析配置、自动调参执行
├── analysis/
│   ├── DetectionPipeline.kt     # 检测管线：FaceSubjectDetector + LumaSubjectDetector + BrightnessEvaluator + ImageTiltEstimator + SceneClassifier
│   ├── FrameAnalyzer.kt         # GuidanceFrameAnalyzer：每帧异步分析，协调各检测器，输出 GuidanceFrame
│   ├── GuidanceEngine.kt        # 规则引擎：场景 → 指导文案 + CameraAction + 方向提示 + 拍摄就绪度 + 目标框
│   ├── GuidanceStabilizer.kt    # 指导去抖：连续 N 帧指纹一致才认定稳定，防闪烁
│   └── PhotoScorer.kt           # 拍后评分：构图/亮度/倾斜/脸部角度 4 维度综合打分 + 重拍建议
├── model/
│   └── CameraGuidanceModels.kt  # 数据模型：GuidanceFrame、CameraAction、DirectionHint、CaptureReadiness、PhotoScore、RetryReason 等
├── sensors/
│   └── DeviceTiltMonitor.kt     # 设备倾斜传感器：Rotation Vector → 实时 roll 角
├── review/
│   └── CloudReviewRepository.kt # 云端点评：multipart 上传 + JSON 响应解析
├── prefs/
│   └── UserPreferencesRepository.kt  # SharedPreferences：云端点评开关 + 服务地址
├── ui/
│   ├── MainViewModel.kt         # ViewModel：相机状态管理 + 云端点评 + 拍后评分状态
│   └── GuidanceOverlayView.kt   # 取景器叠加层：九宫格、水平仪、主体框、方向箭头、目标框、READY 边框
└── util/
    └── ImageUtils.kt            # YUV 平面拷贝、本地摘要生成、JPEG 压缩

app/src/main/res/
├── layout/
│   ├── activity_main.xml        # 主界面布局
│   └── dialog_cloud_settings.xml # 云端设置弹窗
├── drawable/                    # 图标资源
└── values/                      # 字符串、颜色、主题

app/src/test/                    # 单元测试
├── analysis/
│   ├── GuidanceEngineTest.kt
│   ├── GuidanceStabilizerTest.kt
│   └── SceneClassifierTest.kt
└── ui/
    └── DirectionHintAndColorTest.kt

app/src/androidTest/             # 仪表化测试
└── MainActivityTest.kt
```

## 核心数据流

```
相机帧 (YUV_420_888)
  │
  ▼
GuidanceFrameAnalyzer.analyze()
  ├── DeviceTiltMonitor.currentRollDegrees  ──→ 倾斜角
  ├── FaceSubjectDetector.detect()          ──→ 主体框 + 脸部朝向 + 人脸数量
  ├── LumaSubjectDetector.detect()          ──→ 无脸回退主体框
  ├── BrightnessEvaluator.evaluate()        ──→ 亮度状态 + 光源方向
  ├── ImageTiltEstimator.estimate()         ──→ 传感器缺失时的视觉倾斜回退
  └── SceneClassifier.classify()            ──→ 场景类型
  │
  ▼
GuidanceEngine.build()
  ├── 计算 zoomSuggestion（主体面积 vs 场景阈值）
  ├── 计算 recommendationText（考虑光线、脸部朝向、场景特有策略）
  ├── 计算 CameraAction（对焦测光点、曝光补偿、方向提示）
  ├── 计算 CaptureReadiness（构图 0.4 + 亮度 0.3 + 倾斜 0.2 + 缩放 0.1）
  └── 计算 TargetZone（理想主体位置）
  │
  ▼
GuidanceStabilizer.stabilize()
  ├── 连续 stableFrames 帧指纹一致 → isStable = true
  ├── 不稳定帧 → 保留上一次稳定帧的指导文案和方向箭头
  └── CaptureReadiness 独立去抖
  │
  ▼
MainViewModel.onGuidanceFrame()  ──→  StateFlow 更新 UI
  │
  ▼
MainActivity.render()
  ├── GuidanceOverlayView.render()  →  绘制九宫格、水平仪、主体框、方向箭头、目标框、READY 边框
  ├── 更新指导文案、场景/亮度/延迟标签
  └── READY 时快门放大 + 震动
```

## 云端点评接口

**请求**：`POST /v1/photo/review`，`multipart/form-data`

| 字段 | 说明 |
|------|------|
| `file` | 压缩后 JPEG（max 1600px，quality 86） |
| `sceneType` | `portrait` / `selfie` / `pet_or_child` / `daily_generic` |
| `detectionSummary` | 本地检测摘要（场景、亮度、光源方向、倾斜、置信度、主体位置、稳定状态） |
| `deviceModel` | `厂商 型号` |

**响应 JSON**：
```json
{
  "summary": "整体构图稳定，主体曝光略暗。",
  "strengths": ["主体清晰", "构图居中"],
  "issues": ["逆光较重"],
  "suggestions": ["换到更亮的位置", "保留头顶留白"]
}
```

## 未来规划

详见 [plan1.md](plan1.md)：

- **P0**：语音播报（TTS） / 云端通义 VL 实时增强
- **P1**：组合方向提示 / 水平仪智能显隐 / 文案量化
- **P2**：展示页文案对齐
