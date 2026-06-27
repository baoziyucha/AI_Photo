package com.yuxiang.aiphoto.analysis

import com.yuxiang.aiphoto.model.BackgroundMetrics
import com.yuxiang.aiphoto.model.NormalizedRect
import kotlin.math.sqrt

/**
 * 背景干扰检测器（P2 轻量方案）：基于 luma 网格 + faceBox 几何，无需分割模型。
 *
 * 检测四项指标：
 * 1. 亮斑（hasHotspotNearHead）：脸部外扩环带有高光抢戏。
 * 2. 杂乱色块（clutterScore）：环带亮度标准差归一化。
 * 3. 柱子穿头（hasVerticalLineAboveHead）：头顶上方有垂直亮线。
 * 4. 主体分离度（subjectSeparationScore）：脸部与环带亮度对比（低置信度，同色背景失效）。
 *
 * 所有指标仅作软提示（MEDIUM/LOW），不阻断 READY。
 */
object BackgroundEvaluator {

    private const val GRID_COLUMNS = 16
    private const val GRID_ROWS = 12

    /** 亮斑阈值：环带单元格亮度 > 此值视为高光。 */
    private const val HOTSPOT_LUMA = 220f

    /** 亮斑面积阈值：高光单元格占环带比例 > 此值才报。 */
    private const val HOTSPOT_AREA_RATIO = 0.05f

    /** 柱子穿头：列均值高于整体均值 + 此值视为亮线。 */
    private const val VERTICAL_LINE_CONTRAST = 40f

    /** 柱子穿头：列扫描步长（像素）。 */
    private const val COLUMN_STEP = 2

    /**
     * @param luma Y 平面字节数组
     * @param width luma 宽度（像素）
     * @param height luma 高度（像素）
     * @param faceBox 人脸框（归一化坐标）
     * @param grid 16×12 亮度网格（来自 LumaSubjectDetector.computeGrid）
     */
    fun evaluate(
        luma: ByteArray,
        width: Int,
        height: Int,
        faceBox: NormalizedRect,
        grid: Array<FloatArray>,
    ): BackgroundMetrics {
        val ringCells = collectRingCells(faceBox, grid)
        if (ringCells.isEmpty()) {
            return BackgroundMetrics()
        }

        val hotspot = detectHotspot(ringCells)
        val clutter = computeClutter(ringCells)
        val separation = computeSeparation(faceBox, grid, ringCells)
        val verticalLine = detectVerticalLine(luma, width, height, faceBox)

        return BackgroundMetrics(
            clutterScore = clutter,
            hasHotspotNearHead = hotspot,
            hasVerticalLineAboveHead = verticalLine,
            subjectSeparationScore = separation,
        )
    }

    /** 收集脸部外扩环带单元格（扩 2 格，排除脸部本身覆盖的格子）。 */
    private fun collectRingCells(
        faceBox: NormalizedRect,
        grid: Array<FloatArray>,
    ): List<Float> {
        val rows = grid.size
        val cols = grid[0].size
        val startCol = (faceBox.left * cols).toInt().coerceIn(0, cols - 1)
        val endCol = (faceBox.right * cols).toInt().coerceIn(startCol + 1, cols)
        val startRow = (faceBox.top * rows).toInt().coerceIn(0, rows - 1)
        val endRow = (faceBox.bottom * rows).toInt().coerceIn(startRow + 1, rows)

        val margin = 2
        val ringStartCol = (startCol - margin).coerceAtLeast(0)
        val ringEndCol = (endCol + margin).coerceAtMost(cols)
        val ringStartRow = (startRow - margin).coerceAtLeast(0)
        val ringEndRow = (endRow + margin).coerceAtMost(rows)

        val cells = mutableListOf<Float>()
        for (row in ringStartRow until ringEndRow) {
            for (col in ringStartCol until ringEndCol) {
                // 排除脸部本身覆盖的格子
                if (row in startRow until endRow && col in startCol until endCol) continue
                cells += grid[row][col]
            }
        }
        return cells
    }

    /** 亮斑：环带中亮度 > 阈值的格子占比 > 5%。 */
    private fun detectHotspot(ringCells: List<Float>): Boolean {
        if (ringCells.isEmpty()) return false
        val hotspotCount = ringCells.count { it > HOTSPOT_LUMA }
        return hotspotCount.toFloat() / ringCells.size > HOTSPOT_AREA_RATIO
    }

    /** 杂乱度：环带亮度标准差归一化到 0-1。 */
    private fun computeClutter(ringCells: List<Float>): Float {
        if (ringCells.size < 2) return 0f
        val mean = ringCells.average().toFloat()
        val variance = ringCells.map { (it - mean) * (it - mean) }.average().toFloat()
        // 归一化：标准差 / 80（经验值，80 表示杂乱明显）
        return (sqrt(variance) / 80f).coerceIn(0f, 1f)
    }

    /** 主体分离度：脸部均值与环带均值之差归一化（低置信度）。 */
    private fun computeSeparation(
        faceBox: NormalizedRect,
        grid: Array<FloatArray>,
        ringCells: List<Float>,
    ): Float {
        val rows = grid.size
        val cols = grid[0].size
        val startCol = (faceBox.left * cols).toInt().coerceIn(0, cols - 1)
        val endCol = (faceBox.right * cols).toInt().coerceIn(startCol + 1, cols)
        val startRow = (faceBox.top * rows).toInt().coerceIn(0, rows - 1)
        val endRow = (faceBox.bottom * rows).toInt().coerceIn(startRow + 1, rows)

        var faceSum = 0f
        var faceCount = 0
        for (row in startRow until endRow) {
            for (col in startCol until endCol) {
                faceSum += grid[row][col]
                faceCount++
            }
        }
        if (faceCount == 0 || ringCells.isEmpty()) return 0f
        val faceMean = faceSum / faceCount
        val ringMean = ringCells.average().toFloat()
        return (kotlin.math.abs(faceMean - ringMean) / 255f).coerceIn(0f, 1f)
    }

    /** 柱子穿头：在头顶上方区域做列扫描，检测垂直亮线。 */
    private fun detectVerticalLine(
        luma: ByteArray,
        width: Int,
        height: Int,
        faceBox: NormalizedRect,
    ): Boolean {
        // 扫描区域：头顶上方一个脸高的范围
        val faceHeightPx = (faceBox.height * height).toInt()
        val yStart = ((faceBox.top - faceBox.height) * height).toInt().coerceAtLeast(0)
        val yEnd = (faceBox.top * height).toInt().coerceAtMost(height)
        if (yEnd <= yStart || faceHeightPx <= 0) return false

        val xStart = (faceBox.left * width).toInt().coerceIn(0, width - 1)
        val xEnd = (faceBox.right * width).toInt().coerceIn(xStart + 1, width)

        // 逐列计算均值（步长 COLUMN_STEP）
        val columnMeans = mutableListOf<Float>()
        for (x in xStart until xEnd step COLUMN_STEP) {
            var sum = 0f
            var count = 0
            for (y in yStart until yEnd step COLUMN_STEP) {
                sum += luma[y * width + x].toUByte().toInt()
                count++
            }
            if (count > 0) {
                columnMeans += sum / count
            }
        }
        if (columnMeans.size < 5) return false

        val overallMean = columnMeans.average().toFloat()
        // 找局部最大值且显著高于整体均值
        for (i in columnMeans.indices) {
            val left = if (i > 0) columnMeans[i - 1] else 0f
            val right = if (i < columnMeans.lastIndex) columnMeans[i + 1] else 0f
            if (columnMeans[i] > left && columnMeans[i] >= right &&
                columnMeans[i] > overallMean + VERTICAL_LINE_CONTRAST &&
                columnMeans[i] > HOTSPOT_LUMA
            ) {
                return true
            }
        }
        return false
    }
}
