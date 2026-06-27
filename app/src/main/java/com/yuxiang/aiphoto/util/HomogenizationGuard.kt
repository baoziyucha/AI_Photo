package com.yuxiang.aiphoto.util

/**
 * P2-6 同质化守卫：跟踪最近 N 条已播报文案，拒绝与近期过于相似的候选。
 *
 * 解决问题：硬规则建议在不同场景下可能给出语义高度相似的话术
 * （"换个角度" / "挪一步换个角度" / "换个干净点的角度"），
 * 让用户感觉"AI 一直在重复同一句话"。
 *
 * 策略：基于 TextSimilarity.jaccard bigram 相似度，超阈值视为重复。
 * 不阻断硬规则（CRITICAL/HIGH severity），调用方需自行判断是否跳过守卫。
 */
class HomogenizationGuard(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val similarityThreshold: Float = DEFAULT_SIMILARITY_THRESHOLD,
    private val timeWindowMs: Long = DEFAULT_TIME_WINDOW_MS,
) {

    private val recent = ArrayDeque<Pair<String, Long>>()

    /**
     * 检查 [candidate] 是否可播报：与窗口内任意一条相似度 < [similarityThreshold]。
     * 注意：本方法不写入历史，仅做检查。
     */
    @Synchronized
    fun shouldSpeak(candidate: String): Boolean {
        if (candidate.isBlank()) return false
        val now = System.currentTimeMillis()
        // 清理过期项
        recent.removeAll { now - it.second > timeWindowMs }
        // 命中相似则拒绝
        return recent.none { (text, _) ->
            text == candidate ||
                TextSimilarity.jaccard(text, candidate) >= similarityThreshold
        }
    }

    /** 记录已播报文案，写入滑动窗口。 */
    @Synchronized
    fun record(text: String) {
        if (text.isBlank()) return
        recent.addLast(text to System.currentTimeMillis())
        while (recent.size > windowSize) recent.removeFirst()
    }

    /** 原子操作：检查 + 记录。返回 true 表示通过（应立即 record）。 */
    @Synchronized
    fun checkAndRecord(candidate: String): Boolean {
        if (!shouldSpeak(candidate)) return false
        record(candidate)
        return true
    }

    /** 重置历史（场景切换/相机切换时调用）。 */
    @Synchronized
    fun reset() {
        recent.clear()
    }

    companion object {
        const val DEFAULT_WINDOW_SIZE = 6
        const val DEFAULT_SIMILARITY_THRESHOLD = 0.7f
        const val DEFAULT_TIME_WINDOW_MS = 60_000L
    }
}
