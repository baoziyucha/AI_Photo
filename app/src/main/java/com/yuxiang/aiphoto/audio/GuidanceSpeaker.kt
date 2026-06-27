package com.yuxiang.aiphoto.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.yuxiang.aiphoto.model.BrightnessState
import com.yuxiang.aiphoto.model.CaptureReadiness
import com.yuxiang.aiphoto.model.DirectionHint
import com.yuxiang.aiphoto.model.GuidanceFrame
import com.yuxiang.aiphoto.util.HomogenizationGuard
import com.yuxiang.aiphoto.util.Logger
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "GuidanceSpeaker"

/** 抓拍剧本状态机：Idle → Playing(stepIndex) → Captured。 */
sealed class ScriptState {
    data object Idle : ScriptState()
    data class Playing(val stepIndex: Int) : ScriptState()
    data object Captured : ScriptState()
}

/** 剧本单步：文案 + 播完后等待时间。 */
data class ScriptStep(
    val text: String,
    val delayMs: Long,
)

/** 抓拍剧本：步骤序列 + READY 条件。 */
data class CaptureScript(
    val id: String,
    val steps: List<ScriptStep>,
    val readyCondition: (GuidanceFrame) -> Boolean,
)

/**
 * 语音播报封装：基于 TextToSpeech，提供节流、队列打断、开关能力。
 *
 * 播报策略：
 * - readiness 升级到 READY → "可以拍了"
 * - readiness 降级 → 不播报（避免噪音）
 * - 方向提示变化（节流 3s）→ "往左一点" / "下压手机" / "扶正"
 * - 光线异常（首次触发）→ "逆光，转向光源" / "光线偏暗，开补光"
 */
class GuidanceSpeaker(context: Context) {

    private val ready = AtomicBoolean(false)
    private var enabled: Boolean = true

    @Volatile
    private var lastSpokenText: String = ""

    @Volatile
    private var lastSpokenAt: Long = 0L

    @Volatile
    private var lastDirectionHint: DirectionHint = DirectionHint.NONE

    @Volatile
    private var lastBrightnessWarned: Boolean = false

    @Volatile
    private var lastPraiseAt: Long = 0L

    // P2-6 同质化守卫：避免不同规则触发相似话术导致"AI 一直重复"
    private val homogenizationGuard = HomogenizationGuard()

    private lateinit var tts: TextToSpeech

    // 抓拍剧本状态机
    @Volatile
    private var scriptState: ScriptState = ScriptState.Idle
    private val scriptHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile
    private var currentScript: CaptureScript? = null
    @Volatile
    private var latestFrame: GuidanceFrame? = null
    private var onReadyCallback: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
                // 中文缺失时降级为英文，仍不可用则保持静默
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US)
                }
                tts.setSpeechRate(0.95f)
                ready.set(true)
                Logger.d(TAG, "TTS ready, langResult=$result")
            } else {
                Logger.w(TAG, "TTS init failed, status=$status")
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                Logger.w(TAG, "TTS utterance error: $utteranceId")
            }
        })
    }

    /**
     * 根据前后两帧的差异决定是否播报。仅播报关键状态变化，不逐帧播报。
     */
    fun onFrameUpdate(previous: GuidanceFrame, current: GuidanceFrame) {
        if (!enabled || !ready.get()) return

        // 1. readiness 升级到 READY
        if (current.captureReadiness == CaptureReadiness.READY &&
            previous.captureReadiness != CaptureReadiness.READY
        ) {
            speak("可以拍了", throttleMs = 0L)
            return
        }

        // 2. 方向提示变化（节流 3s）
        val currentHint = current.cameraAction.directionHint
        if (currentHint != lastDirectionHint && currentHint != DirectionHint.NONE) {
            val hintSpeech = directionHintToSpeech(currentHint)
            if (hintSpeech != null && speak(hintSpeech, throttleMs = DIRECTION_THROTTLE_MS)) {
                lastDirectionHint = currentHint
                return
            }
        }
        if (currentHint == DirectionHint.NONE) {
            lastDirectionHint = DirectionHint.NONE
        }

        // 3. 光线异常首次触发
        val brightnessWarned = current.brightnessState.let {
            it == BrightnessState.BACKLIT ||
                it == BrightnessState.LOW_LIGHT
        }
        if (brightnessWarned && !lastBrightnessWarned) {
            val lightSpeech = when (current.brightnessState) {
                BrightnessState.BACKLIT -> "逆光，转向光源"
                BrightnessState.LOW_LIGHT -> "光线偏暗，开补光"
                else -> null
            }
            if (lightSpeech != null) {
                speak(lightSpeech, throttleMs = LIGHT_THROTTLE_MS)
            }
        }
        lastBrightnessWarned = brightnessWarned

        // 4. P2 主动型夸赞：前侧光人像黄金光位（节流 30s，避免重复刷屏）
        val now = System.currentTimeMillis()
        if (current.primaryIssue?.id == "light.front_side_good" &&
            now - lastPraiseAt >= PRAISE_THROTTLE_MS
        ) {
            // praise 走守卫也合理：避免与近期"换个角度"等冲撞，bypass 节流但走守卫
            if (speak("现在光线很好，适合拍人像", throttleMs = 0L, bypassGuard = false)) {
                lastPraiseAt = now
            }
        }

        // 透传最新帧给抓拍剧本状态机，供 readyCondition 使用
        updateFrameForScript(current)
    }

    /**
     * 显式播报一条文案（如拍照完成、剧本步骤）。
     * 跳过同质化守卫：这些是用户主动触发或剧本预设的，不应被抑制。
     */
    fun announce(text: String) {
        speak(text, throttleMs = 0L, bypassGuard = true)
    }

    /** 启动抓拍剧本；每步播报后等待 delayMs，剧本结束后 readyCondition 满足时回调 onReady。 */
    fun startScript(
        script: CaptureScript,
        onReady: () -> Unit,
    ) {
        stopScript()
        currentScript = script
        onReadyCallback = onReady
        scriptState = ScriptState.Playing(0)
        Logger.d(TAG, "startScript: id=${script.id}, steps=${script.steps.size}")
        playStep(0, script)
    }

    /** 实时帧更新供剧本 readyCondition 使用。 */
    fun updateFrameForScript(frame: GuidanceFrame) {
        latestFrame = frame
    }

    fun stopScript() {
        scriptHandler.removeCallbacksAndMessages(null)
        currentScript = null
        onReadyCallback = null
        scriptState = ScriptState.Idle
    }

    private fun playStep(index: Int, script: CaptureScript) {
        if (index >= script.steps.size) {
            // 剧本结束：检查 readyCondition，满足则触发 onReady
            val frame = latestFrame
            if (frame != null && script.readyCondition(frame)) {
                scriptState = ScriptState.Captured
                Logger.d(TAG, "playStep: script done, readyCondition met, trigger onReady")
                onReadyCallback?.invoke()
            } else {
                scriptState = ScriptState.Idle
                Logger.d(TAG, "playStep: script done, readyCondition not met")
            }
            return
        }
        val step = script.steps[index]
        scriptState = ScriptState.Playing(index)
        Logger.d(TAG, "playStep: index=$index, text=\"${step.text}\"")
        announce(step.text)
        scriptHandler.postDelayed({
            playStep(index + 1, script)
        }, step.delayMs + estimateSpeakMs(step.text))
    }

    /** 估算中文 TTS 播报时长（speechRate=0.95f 时约 250ms/字）。 */
    private fun estimateSpeakMs(text: String): Long {
        return (text.length * 250L).coerceAtLeast(500L)
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            tts.stop()
            stopScript()
        }
    }

    fun shutdown() {
        stopScript()
        tts.stop()
        tts.shutdown()
    }

    private fun speak(text: String, throttleMs: Long, bypassGuard: Boolean = false): Boolean {
        if (!enabled || !ready.get() || text.isBlank()) return false
        val now = System.currentTimeMillis()
        if (throttleMs > 0 && text == lastSpokenText && now - lastSpokenAt < throttleMs) {
            return false
        }
        // P2-6 同质化守卫：与近期播报过于相似则跳过（除非显式 bypass）
        if (!bypassGuard && !homogenizationGuard.checkAndRecord(text)) {
            Logger.d(TAG, "homogenization guard skipped: \"$text\"")
            return false
        }
        // QUEUE_FLUSH 打断旧文案，避免堆积
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "guidance_$now")
        lastSpokenText = text
        lastSpokenAt = now
        return true
    }

    private fun directionHintToSpeech(hint: DirectionHint): String? = when (hint) {
        DirectionHint.MOVE_LEFT -> "往左一点"
        DirectionHint.MOVE_RIGHT -> "往右一点"
        DirectionHint.MOVE_UP -> "抬高手机"
        DirectionHint.MOVE_DOWN -> "下压手机"
        DirectionHint.TILT_LEFT -> "扶正"
        DirectionHint.TILT_RIGHT -> "扶正"
        DirectionHint.NONE -> null
    }

    companion object {
        private const val DIRECTION_THROTTLE_MS = 3000L
        private const val LIGHT_THROTTLE_MS = 5000L
        private const val PRAISE_THROTTLE_MS = 30000L

        /** 内置剧本：自然笑头像（文档 9.2 节）。 */
        internal val NaturalSmileHeadshotScript = CaptureScript(
            id = "natural_smile_headshot",
            steps = listOf(
                ScriptStep("肩膀侧一点，身体不用正对镜头。", 1500L),
                ScriptStep("脸转回来一点，看镜头上方。", 1500L),
                ScriptStep("下巴轻轻收住，嘴角放松。", 1500L),
                ScriptStep("闭眼，深呼吸。", 1800L),
                ScriptStep("3、2、1，睁眼看镜头。", 1200L),
                ScriptStep("很好，保持，拍。", 0L),
            ),
            readyCondition = { frame ->
                // READY 条件：未眨眼（双眼最小睁眼概率 >= 0.4 或缺失信息）
                val minEye = listOfNotNull(frame.leftEyeOpenProb, frame.rightEyeOpenProb).minOrNull()
                minEye == null || minEye >= 0.4f
            },
        )

        /** 内置剧本：近景特写头像（P1 扩展）。 */
        internal val CloseupHeadshotScript = CaptureScript(
            id = "closeup_headshot",
            steps = listOf(
                ScriptStep("肩膀放松，脸正对镜头。", 1500L),
                ScriptStep("下巴轻轻收住，看镜头上方。", 1500L),
                ScriptStep("嘴角放松，不用刻意笑。", 1500L),
                ScriptStep("眼睛有神，看镜头。", 1500L),
                ScriptStep("深呼吸，3、2、1。", 1200L),
                ScriptStep("保持，拍。", 0L),
            ),
            readyCondition = { frame ->
                val minEye = listOfNotNull(frame.leftEyeOpenProb, frame.rightEyeOpenProb).minOrNull()
                minEye == null || minEye >= 0.4f
            },
        )

        /** 内置剧本：窗边侧光半身（文档 9.3 节）。 */
        internal val WindowSideLightHalfBodyScript = CaptureScript(
            id = "window_side_light_half_body",
            steps = listOf(
                ScriptStep("站到窗边，让光从侧前方照到脸。", 1800L),
                ScriptStep("身体侧 30 度，肩膀放松。", 1500L),
                ScriptStep("脸转向光一点，让眼睛里有光。", 1500L),
                ScriptStep("看窗外，再慢慢看回镜头。", 1800L),
                ScriptStep("保持这个角度，拍。", 0L),
            ),
            readyCondition = { frame ->
                // READY 条件：未眨眼且非严重阴阳脸（ratio < 2.0）
                val minEye = listOfNotNull(frame.leftEyeOpenProb, frame.rightEyeOpenProb).minOrNull()
                val eyeOk = minEye == null || minEye >= 0.4f
                val ratio = frame.faceLightMetrics?.faceLightRatio
                val lightOk = ratio == null || ratio < 2.0f
                eyeOk && lightOk
            },
        )

        /** 内置剧本：旅行环境人像（P1 扩展）。 */
        internal val TravelEnvironmentalScript = CaptureScript(
            id = "travel_environmental",
            steps = listOf(
                ScriptStep("走到画面三分之一处，侧身站好。", 1800L),
                ScriptStep("看向远处风景，不要看镜头。", 1500L),
                ScriptStep("手自然放口袋或扶帽子。", 1500L),
                ScriptStep("慢慢回头，看镜头。", 1500L),
                ScriptStep("保持这个动作，拍。", 0L),
            ),
            readyCondition = { frame ->
                val minEye = listOfNotNull(frame.leftEyeOpenProb, frame.rightEyeOpenProb).minOrNull()
                minEye == null || minEye >= 0.4f
            },
        )
    }
}
