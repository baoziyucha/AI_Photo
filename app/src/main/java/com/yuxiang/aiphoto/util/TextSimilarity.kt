package com.yuxiang.aiphoto.util

/**
 * P2-6 文本相似度工具：基于字符 bigram 的 Jaccard 系数。
 *
 * 中英文都适用：中文按相邻 2 字切分（"逆光转向光源" → {逆光, 光转, 转向, 向光, 光源}），
 * 英文按空白分词后再做 bigram。返回 0-1，1 表示完全相同。
 */
object TextSimilarity {

    /** Jaccard 相似度：|A ∩ B| / |A ∪ B|，基于字符 bigram。 */
    fun jaccard(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isBlank() || b.isBlank()) return 0f
        val setA = bigrams(a)
        val setB = bigrams(b)
        if (setA.isEmpty() || setB.isEmpty()) return 0f
        val inter = setA.intersect(setB).size.toFloat()
        val union = setA.union(setB).size.toFloat()
        return if (union == 0f) 0f else inter / union
    }

    /** 提取 bigram 集合；过短文本退化为单字符集。 */
    private fun bigrams(s: String): Set<String> {
        val cleaned = s.trim().replace(Regex("\\s+"), "")
        if (cleaned.length < 2) return setOf(cleaned)
        return (0..cleaned.length - 2).map { cleaned.substring(it, it + 2) }.toSet()
    }
}
