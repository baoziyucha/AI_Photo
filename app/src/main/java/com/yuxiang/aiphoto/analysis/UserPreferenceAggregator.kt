package com.yuxiang.aiphoto.analysis

import com.yuxiang.aiphoto.model.GuidanceFrame
import com.yuxiang.aiphoto.model.PhotoScore
import com.yuxiang.aiphoto.model.UserPreference
import com.yuxiang.aiphoto.util.Logger

/**
 * P2-7 个性化记忆聚合器：从历史拍摄中聚合用户偏好。
 *
 * 聚合维度（需求文档 8.2 节）：
 * 1. 最佳侧脸：高分照片的 headEulerY 中位数
 * 2. 最佳笑容：高分照片的 smilingProbability 中位数
 * 3. 常见问题：所有照片 issues 按 id 计数取 top 3
 * 4. 计数器：totalCaptures / highScoreCaptures
 *
 * 仅聚合"硬规则通过"的照片（baseScore >= HIGH_SCORE_THRESHOLD），
 * 避免把眨眼/严重欠曝等废片统计进"最佳"。
 */
class UserPreferenceAggregator(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
) {

    /**
     * 用一次新的拍摄结果更新用户偏好。
     *
     * @param current 当前持久化的偏好
     * @param score 本次照片评分
     * @param frame 本次照片对应的 GuidanceFrame（可能为 null，如纯风景）
     * @return 更新后的偏好
     */
    fun aggregate(
        current: UserPreference,
        score: PhotoScore,
        frame: GuidanceFrame?,
    ): UserPreference {
        val isHighScore = score.baseScore >= HIGH_SCORE_THRESHOLD
        val newTotal = current.totalCaptures + 1
        val newHighScore = current.highScoreCaptures + if (isHighScore) 1 else 0

        // 仅高分照片进入"最佳侧脸/笑容"窗口
        val newHeadEulerY = if (isHighScore && frame?.headEulerY != null) {
            updateBestValue(current.bestHeadEulerY, frame.headEulerY)
        } else {
            current.bestHeadEulerY
        }
        val newSmilingProb = if (isHighScore && frame?.smilingProbability != null) {
            updateBestValue(current.bestSmilingProbability, frame.smilingProbability)
        } else {
            current.bestSmilingProbability
        }

        // 常见问题：累计 issue id 频次
        val newCommonIssues = updateCommonIssues(current.commonIssueIds, score)

        Logger.d(
            TAG,
            "aggregate: total=$newTotal, highScore=$newHighScore, " +
                "bestYaw=$newHeadEulerY, bestSmile=$newSmilingProb, " +
                "commonIssues=${newCommonIssues.take(3)}",
        )

        return current.copy(
            bestHeadEulerY = newHeadEulerY,
            bestSmilingProbability = newSmilingProb,
            commonIssueIds = newCommonIssues.take(3),
            totalCaptures = newTotal,
            highScoreCaptures = newHighScore,
        )
    }

    /**
     * 滑动窗口中位数更新：保留最近 [windowSize] 个值的近似中位数。
     * 简化实现：用指数加权移动平均（EWMA），alpha = 1/windowSize，
     * 避免维护显式窗口的存储与并发开销。
     */
    private fun updateBestValue(previous: Float?, sample: Float): Float {
        if (previous == null) return sample
        val alpha = 1f / windowSize.coerceAtLeast(1)
        return previous * (1 - alpha) + sample * alpha
    }

    /**
     * 常见问题更新：合并历史 top issues 与本次 issues 的频次。
     * 简化实现：本次 issues 优先级提升，历史的逐步衰减。
     */
    private fun updateCommonIssues(
        history: List<String>,
        score: PhotoScore,
    ): List<String> {
        if (score.issues.isEmpty()) return history

        // 统计本次 issue id
        val currentIds = score.issues.map { it.id }
        // 合并计数：历史每个 id 计 1 票，本次每个 id 计 2 票（提升新鲜度）
        val counts = mutableMapOf<String, Int>()
        history.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        currentIds.forEach { counts[it] = (counts[it] ?: 0) + 2 }

        // 按票数降序取 top 5（保留冗余避免列表过短）
        return counts.entries
            .sortedByDescending { it.value }
            .take(MAX_COMMON_ISSUES_KEEP)
            .map { it.key }
    }

    companion object {
        private const val TAG = "UserPreferenceAggregator"

        /** 高分阈值：baseScore >= 80 才进入"最佳"统计。 */
        const val HIGH_SCORE_THRESHOLD = 80

        /** EWMA 窗口大小：最近 5 张高分照片的影响权重。 */
        const val DEFAULT_WINDOW_SIZE = 5

        /** 常见问题保留数量（实际对外暴露 top 3）。 */
        const val MAX_COMMON_ISSUES_KEEP = 5
    }
}
