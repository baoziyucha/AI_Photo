package com.yuxiang.aiphoto.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomogenizationGuardTest {

    @Test
    fun identicalText_isRejected() {
        val guard = HomogenizationGuard()
        assertThat(guard.checkAndRecord("换个角度")).isTrue()
        assertThat(guard.checkAndRecord("换个角度")).isFalse()
    }

    @Test
    fun verySimilarText_isRejected() {
        val guard = HomogenizationGuard(similarityThreshold = 0.7f)
        assertThat(guard.checkAndRecord("换个角度")).isTrue()
        // 仅多一个字，bigram 高度重叠
        assertThat(guard.checkAndRecord("换个角度吧")).isFalse()
    }

    @Test
    fun differentText_isAccepted() {
        val guard = HomogenizationGuard()
        assertThat(guard.checkAndRecord("往左一点")).isTrue()
        assertThat(guard.checkAndRecord("抬高手机")).isTrue()
        assertThat(guard.checkAndRecord("下压手机")).isTrue()
    }

    @Test
    fun windowSize_evictsOldest() {
        val guard = HomogenizationGuard(windowSize = 2)
        assertThat(guard.checkAndRecord("第一句话")).isTrue()
        assertThat(guard.checkAndRecord("第二句话")).isTrue()
        // 第三条进入后，第一条被驱逐，因此与第一条相同的候选应再次通过
        assertThat(guard.checkAndRecord("第三句话")).isTrue()
        assertThat(guard.checkAndRecord("第一句话")).isTrue()
    }

    @Test
    fun reset_clearsHistory() {
        val guard = HomogenizationGuard()
        guard.checkAndRecord("重复话术")
        guard.reset()
        assertThat(guard.checkAndRecord("重复话术")).isTrue()
    }

    @Test
    fun textSimilarity_jaccard_bounds() {
        assertThat(TextSimilarity.jaccard("", "")).isEqualTo(0f)
        assertThat(TextSimilarity.jaccard("abc", "abc")).isEqualTo(1f)
        assertThat(TextSimilarity.jaccard("完全不同", "毫不相干")).isLessThan(0.3f)
        assertThat(TextSimilarity.jaccard("换个角度", "换个角度吧")).isGreaterThan(0.7f)
    }
}
