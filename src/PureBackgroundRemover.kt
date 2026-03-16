package com.jisuyatu.solidbgcut

/**
 * 纯色背景抠图算法
 *
 * 特点：
 * - 纯 Java/Kotlin 实现，无 Android 依赖
 * - 可跨平台使用（Windows、鸿蒙、Linux 等）
 * - 可独立开源
 *
 * 算法原理：
 * 1. 检测图片边缘像素，判断背景色
 * 2. 使用颜色相似度阈值进行区域分割
 * 3. 应用形态学操作优化边缘
 * 4. 生成透明背景
 */
class PureBackgroundRemover {

    /**
     * 移除纯色背景（增强版）
     *
     * @param pixels 图片像素数组 (ARGB)
     * @param width 图片宽度
     * @param height 图片高度
     * @param backgroundColor 背景颜色 (ARGB)，如果为 null 则自动检测
     * @param tolerance 颜色容差 (0-255)，默认 30
     * @return 处理后的像素数组 (ARGB，背景透明)
     */
    fun removeBackground(
        pixels: IntArray,
        width: Int,
        height: Int,
        backgroundColor: Int? = null,
        tolerance: Int = 30
    ): IntArray {
        val bgColor = backgroundColor ?: detectBackgroundColorEnhanced(pixels, width, height)

        val bgR = (bgColor shr 16) and 0xFF
        val bgG = (bgColor shr 8) and 0xFF
        val bgB = bgColor and 0xFF

        val mask = createPreciseMask(pixels, width, height, bgR, bgG, bgB, tolerance)
        val cleanedMask = applyMorphology(mask, width, height)
        val protectedMask = protectForegroundRegions(cleanedMask, pixels, width, height, bgR, bgG, bgB, tolerance)

        var result = applyMaskToPixels(pixels, protectedMask, width, height, tolerance)
        result = enhanceEdgeSmoothing(result, width, height)
        result = removeFineResidues(result, pixels, width, height, bgR, bgG, bgB, tolerance)

        return result
    }

    private fun detectBackgroundColorEnhanced(pixels: IntArray, width: Int, height: Int): Int {
        val edgePixels = mutableListOf<Int>()
        val sampleCount = minOf(width, height) / 5

        for (y in 0 until minOf(5, height / 10)) {
            for (x in 0 until width step maxOf(1, width / sampleCount)) {
                edgePixels.add(pixels[y * width + x])
            }
        }

        for (y in maxOf(0, height - minOf(5, height / 10)) until height) {
            for (x in 0 until width step maxOf(1, width / sampleCount)) {
                edgePixels.add(pixels[y * width + x])
            }
        }

        for (x in 0 until minOf(5, width / 10)) {
            for (y in 0 until height step maxOf(1, height / sampleCount)) {
                edgePixels.add(pixels[y * width + x])
            }
        }

        for (x in maxOf(0, width - minOf(5, width / 10)) until width) {
            for (y in 0 until height step maxOf(1, height / sampleCount)) {
                edgePixels.add(pixels[y * width + x])
            }
        }

        return findDominantColor(edgePixels)
    }

    private fun createPreciseMask(
        pixels: IntArray,
        width: Int,
        height: Int,
        bgR: Int,
        bgG: Int,
        bgB: Int,
        tolerance: Int
    ): FloatArray {
        val mask = FloatArray(pixels.size)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            val distance = calculatePerceptualColorDistance(r, g, b, bgR, bgG, bgB)

            if (distance <= tolerance) {
                mask[i] = 0f
            } else {
                val fadeRange = tolerance * 2.0
                if (distance <= fadeRange) {
                    val ratio = ((distance - tolerance) / tolerance).coerceIn(0.0, 1.0)
                    mask[i] = ratio.toFloat()
                } else {
                    mask[i] = 1f
                }
            }
        }

        return mask
    }

    private fun calculatePerceptualColorDistance(
        r1: Int, g1: Int, b1: Int,
        r2: Int, g2: Int, b2: Int
    ): Double {
        val dr = (r1 - r2).toDouble()
        val dg = (g1 - g2).toDouble()
        val db = (b1 - b2).toDouble()

        val weightR = 0.3
        val weightG = 0.59
        val weightB = 0.11

        return kotlin.math.sqrt(weightR * dr * dr + weightG * dg * dg + weightB * db * db)
    }

    private fun findDominantColor(colors: List<Int>): Int {
        if (colors.isEmpty()) return 0xFFFFFFFF.toInt()

        val clusters = mutableListOf<Int>()
        val clusterSizes = mutableListOf<Int>()

        for (i in 0 until minOf(3, colors.size)) {
            clusters.add(colors[i * (colors.size / 3)])
            clusterSizes.add(0)
        }

        val assignments = IntArray(colors.size)
        for ((idx, color) in colors.withIndex()) {
            var minDist = Double.MAX_VALUE
            var bestCluster = 0

            for (i in clusters.indices) {
                val clusterColor = clusters[i]
                val cr = (clusterColor shr 16) and 0xFF
                val cg = (clusterColor shr 8) and 0xFF
                val cb = clusterColor and 0xFF
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                val dist = calculateColorDistance(r, g, b, cr, cg, cb)
                if (dist < minDist) {
                    minDist = dist
                    bestCluster = i
                }
            }

            assignments[idx] = bestCluster
            clusterSizes[bestCluster]++
        }

        val maxCluster = clusterSizes.indices.maxByOrNull { clusterSizes[it] } ?: 0
        var sumR = 0
        var sumG = 0
        var sumB = 0
        var count = 0

        for ((idx, color) in colors.withIndex()) {
            if (assignments[idx] == maxCluster) {
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                sumR += r
                sumG += g
                sumB += b
                count++
            }
        }

        if (count == 0) return colors[0]

        val avgR = (sumR / count) and 0xFF
        val avgG = (sumG / count) and 0xFF
        val avgB = (sumB / count) and 0xFF

        return (0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
    }

    private fun calculateColorDistance(
        r1: Int, g1: Int, b1: Int,
        r2: Int, g2: Int, b2: Int
    ): Double {
        val dr = (r1 - r2).toDouble()
        val dg = (g1 - g2).toDouble()
        val db = (b1 - b2).toDouble()
        return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
    }

    private fun applyMorphology(mask: FloatArray, width: Int, height: Int): FloatArray {
        val eroded = erode(mask, width, height)
        return dilate(eroded, width, height)
    }

    private fun erode(mask: FloatArray, width: Int, height: Int): FloatArray {
        val result = FloatArray(mask.size)
        val radius = 1

        for (y in 0 until height) {
            for (x in 0 until width) {
                var minVal = 1f
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until width && ny in 0 until height) {
                            val v = mask[ny * width + nx]
                            if (v < minVal) {
                                minVal = v
                            }
                        }
                    }
                }
                result[y * width + x] = minVal
            }
        }

        return result
    }

    private fun dilate(mask: FloatArray, width: Int, height: Int): FloatArray {
        val result = FloatArray(mask.size)
        val radius = 1

        for (y in 0 until height) {
            for (x in 0 until width) {
                var maxVal = 0f
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until width && ny in 0 until height) {
                            val v = mask[ny * width + nx]
                            if (v > maxVal) {
                                maxVal = v
                            }
                        }
                    }
                }
                result[y * width + x] = maxVal
            }
        }

        return result
    }

    private fun protectForegroundRegions(
        mask: FloatArray,
        pixels: IntArray,
        width: Int,
        height: Int,
        bgR: Int,
        bgG: Int,
        bgB: Int,
        tolerance: Int
    ): FloatArray {
        val result = mask.copyOf()
        val visited = BooleanArray(mask.size)
        val queue = ArrayDeque<Int>()

        val threshold = 0.6f

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (!visited[idx] && mask[idx] > threshold) {
                    var fgCount = 0
                    var totalCount = 0

                    queue.clear()
                    queue.add(idx)
                    visited[idx] = true

                    while (queue.isNotEmpty()) {
                        val cur = queue.removeFirst()
                        totalCount++
                        if (mask[cur] > 0.8f) fgCount++

                        val cx = cur % width
                        val cy = cur / width

                        for (dy in -1..1) {
                            for (dx in -1..1) {
                                if (dx == 0 && dy == 0) continue
                                val nx = cx + dx
                                val ny = cy + dy
                                if (nx in 0 until width && ny in 0 until height) {
                                    val nIdx = ny * width + nx
                                    if (!visited[nIdx] && mask[nIdx] > threshold) {
                                        visited[nIdx] = true
                                        queue.add(nIdx)
                                    }
                                }
                            }
                        }
                    }

                    if (fgCount / (totalCount.toFloat()) > 0.6f) {
                        queue.clear()
                        queue.add(idx)

                        while (queue.isNotEmpty()) {
                            val cur = queue.removeFirst()
                            result[cur] = maxOf(result[cur], 0.9f)

                            val cx = cur % width
                            val cy = cur / width

                            for (dy in -1..1) {
                                for (dx in -1..1) {
                                    if (dx == 0 && dy == 0) continue
                                    val nx = cx + dx
                                    val ny = cy + dy
                                    if (nx in 0 until width && ny in 0 until height) {
                                        val nIdx = ny * width + nx
                                        if (mask[nIdx] > threshold && result[nIdx] < 0.9f) {
                                            result[nIdx] = maxOf(result[nIdx], 0.9f)
                                            queue.add(nIdx)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return result
    }

    private fun applyMaskToPixels(
        pixels: IntArray,
        mask: FloatArray,
        width: Int,
        height: Int,
        tolerance: Int
    ): IntArray {
        val result = IntArray(pixels.size)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val a = (pixel shr 24) and 0xFF
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            val m = mask[i].coerceIn(0f, 1f)
            val newA = (a * m).toInt().coerceIn(0, 255)

            result[i] = (newA shl 24) or (r shl 16) or (g shl 8) or b
        }

        return result
    }

    private fun enhanceEdgeSmoothing(pixels: IntArray, width: Int, height: Int): IntArray {
        val result = pixels.copyOf()
        val radius = 1

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val pixel = pixels[idx]
                val a = (pixel shr 24) and 0xFF

                if (a in 1..254) {
                    var sumA = 0
                    var count = 0

                    for (dy in -radius..radius) {
                        for (dx in -radius..radius) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx in 0 until width && ny in 0 until height) {
                                val nIdx = ny * width + nx
                                val na = (pixels[nIdx] shr 24) and 0xFF
                                sumA += na
                                count++
                            }
                        }
                    }

                    val avgA = (sumA / count).coerceIn(0, 255)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    result[idx] = (avgA shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        return result
    }

    private fun removeFineResidues(
        resultPixels: IntArray,
        originalPixels: IntArray,
        width: Int,
        height: Int,
        bgR: Int,
        bgG: Int,
        bgB: Int,
        tolerance: Int
    ): IntArray {
        val result = resultPixels.copyOf()

        for (i in result.indices) {
            val pixel = result[i]
            val a = (pixel shr 24) and 0xFF
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            val distance = calculatePerceptualColorDistance(r, g, b, bgR, bgG, bgB)

            if (distance < tolerance * 0.7 && a < 255) {
                result[i] = 0
            }
        }

        return result
    }
}

