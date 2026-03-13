# AI 拍照辅助

安卓原生 `Kotlin + CameraX` MVP，相机预览、拍照、本地实时构图分析和按张触发的云端点评都已经打通。

## 当前能力
- 实时预览与静态拍照保存到系统相册 `Pictures/AiPhoto`
- 本地人脸检测 + 亮度/逆光/倾斜/主体显著性分析
- 构图建议：靠近、后退、左右微调、抬高压低、扶正
- 基础自动调参：主体跟随对焦/测光，曝光补偿小步调整
- 前后摄切换
- 云端点评开关与服务地址配置，`POST /v1/photo/review`

## 技术栈
- 语言与构建：
  `Kotlin 1.9.24`、`Java 17`、`Gradle 8.7`、`Android Gradle Plugin 8.6.1`
- Android 基础：
  `minSdk 29`、`targetSdk 35`、AndroidX、Material 3、ConstraintLayout、ViewBinding
- 架构与状态管理：
  `Activity + AndroidViewModel`、`StateFlow`、Kotlin Coroutines
- 相机能力：
  `CameraX 1.4.1`（`Preview` / `ImageCapture` / `ImageAnalysis`）+
  `Camera2Interop`
- 本地 AI / 视觉分析：
  `ML Kit Face Detection` +
  自定义亮度、逆光、倾斜、主体显著性与构图规则引擎
- 网络与数据：
  `OkHttp 4.12.0`、`Gson 2.11.0`、`SharedPreferences`
- 测试：
  `JUnit 4`、`Truth`、`Espresso`、AndroidX Test

更准确地说，这个项目当前是“安卓原生 CameraX 应用 + 轻量本地视觉规则引擎 + 可选云端点评接口”，不是 Flutter、React Native 或大模型直驱的方案。

## 构建与运行
```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

默认使用当前机器的 Android SDK 路径 `/Users/yuxiang/Library/Android/sdk`。如果后续换机器，更新 [local.properties](/Users/yuxiang/developer/my_project/ai-photo/local.properties#L1) 即可。

## 云端点评接口
请求：
- 方法：`POST /v1/photo/review`
- 内容：`multipart/form-data`
- 字段：`file`、`sceneType`、`detectionSummary`、`deviceModel`

响应 JSON：
```json
{
  "summary": "整体构图稳定，主体曝光略暗。",
  "strengths": ["主体清晰", "构图居中"],
  "issues": ["逆光较重"],
  "suggestions": ["换到更亮的位置", "保留头顶留白"]
}
```

## 代码结构
- [MainActivity.kt](/Users/yuxiang/developer/my_project/ai-photo/app/src/main/java/com/yuxiang/aiphoto/MainActivity.kt) 负责权限、相机页交互和状态渲染
- [AiCameraManager.kt](/Users/yuxiang/developer/my_project/ai-photo/app/src/main/java/com/yuxiang/aiphoto/camera/AiCameraManager.kt) 负责 CameraX 绑定、拍照和自动调参执行
- [FrameAnalyzer.kt](/Users/yuxiang/developer/my_project/ai-photo/app/src/main/java/com/yuxiang/aiphoto/analysis/FrameAnalyzer.kt) 负责每帧分析
- [GuidanceEngine.kt](/Users/yuxiang/developer/my_project/ai-photo/app/src/main/java/com/yuxiang/aiphoto/analysis/GuidanceEngine.kt) 负责把检测结果映射为提示与相机动作
- [CloudReviewRepository.kt](/Users/yuxiang/developer/my_project/ai-photo/app/src/main/java/com/yuxiang/aiphoto/review/CloudReviewRepository.kt) 负责按张上传点评
