/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.health.openscale.ui.screen.camera

import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight first-pass reader for a tightly framed, three-digit seven-segment display.
 * It intentionally ignores the decimal point and unit indicator.
 */
internal object ScaleDisplayRecognizer {
    private val digitByMask = mapOf(
        0b0111111 to '0', 0b0000110 to '1', 0b1011011 to '2',
        0b1001111 to '3', 0b1100110 to '4', 0b1101101 to '5',
        0b1111101 to '6', 0b0000111 to '7', 0b1111111 to '8',
        0b1101111 to '9'
    )

    // Segment sample rectangles in normalized digit coordinates: A, B, C, D, E, F, G.
    private val zones = listOf(
        floatArrayOf(.25f,.08f,.75f,.20f),
        floatArrayOf(.68f,.14f,.84f,.43f),
        floatArrayOf(.68f,.57f,.84f,.86f),
        floatArrayOf(.25f,.80f,.75f,.92f),
        floatArrayOf(.16f,.57f,.32f,.86f),
        floatArrayOf(.16f,.14f,.32f,.43f),
        floatArrayOf(.25f,.44f,.75f,.56f)
    )


    data class Detection(val digits: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val score: Int)

    /**
     * Searches the complete image for a likely three-digit display instead of assuming
     * that the display is centered. Returns the best candidate window that decodes.
     */
    fun find(gray: ByteArray, width: Int, height: Int): Detection? {
        if (width < 80 || height < 60 || gray.size < width * height) return null
        // Search at <=320px on the longest side to keep the sliding-window scan lightweight.
        val scale = maxOf(width, height).toFloat() / 320f
        if (scale > 1f) {
            val sw = (width / scale).toInt().coerceAtLeast(1)
            val sh = (height / scale).toInt().coerceAtLeast(1)
            val small = ByteArray(sw * sh)
            for (y in 0 until sh) for (x in 0 until sw) {
                small[y * sw + x] = gray[(y * scale).toInt() * width + (x * scale).toInt()]
            }
            val found = find(small, sw, sh) ?: return null
            return Detection(found.digits, (found.left * scale).toInt(), (found.top * scale).toInt(),
                (found.right * scale).toInt(), (found.bottom * scale).toInt(), found.score)
        }
        var best: Detection? = null
        // Candidate windows are sized around the expected proportions of three digits.
        // Coarse-to-fine scanning keeps this lightweight without OpenCV/ML.
        val minH = max(40, height / 7)
        val maxH = min(height, width)
        var candidateH = minH
        while (candidateH <= maxH) {
            val candidateW = min(width, candidateH)
            if (candidateW >= 60) {
                val stepX = max(8, candidateW / 8)
                val stepY = max(8, candidateH / 8)
                var top = 0
                while (top + candidateH <= height) {
                    var left = 0
                    while (left + candidateW <= width) {
                        val crop = ByteArray(candidateW * candidateH)
                        var idx = 0
                        var sum = 0L
                        var sumSq = 0L
                        for (y in top until top + candidateH) {
                            val start = y * width + left
                            for (x in 0 until candidateW) {
                                val v = gray[start + x].toInt() and 0xff
                                crop[idx++] = gray[start + x]
                                sum += v
                                sumSq += v.toLong() * v
                            }
                        }
                        val mean = sum.toDouble() / crop.size
                        val variance = sumSq.toDouble() / crop.size - mean * mean
                        if (variance > 120.0) {
                            val digits = read(crop, candidateW, candidateH)
                            if (digits != null) {
                                // Prefer candidates with stronger contrast, while avoiding
                                // windows that are almost uniformly black or white.
                                val score = min(1000, variance.toInt())
                                if (best == null || score > best.score) {
                                    best = Detection(digits, left, top, left + candidateW, top + candidateH, score)
                                }
                            }
                        }
                        left += stepX
                    }
                    top += stepY
                }
            }
            candidateH = (candidateH * 1.22f).toInt().coerceAtLeast(candidateH + 1)
        }
        return best
    }

    fun read(gray: ByteArray, width: Int, height: Int): String? {
        if (width < 60 || height < 30 || gray.size < width * height) return null
        // Crop the central display band. User aligns the display with the on-screen guide.
        val left = (width * .12f).toInt()
        val right = (width * .88f).toInt()
        val top = (height * .25f).toInt()
        val bottom = (height * .75f).toInt()
        val cw = right - left
        val ch = bottom - top
        if (cw < 45 || ch < 20) return null
        val digitW = cw / 3
        val histogram = IntArray(256)
        var total = 0
        for (y in top until bottom) for (x in left until right) {
            histogram[gray[y * width + x].toInt() and 0xff]++
            total++
        }
        fun percentile(target: Int): Int {
            var sum = 0
            for (value in histogram.indices) {
                sum += histogram[value]
                if (sum >= target) return value
            }
            return 255
        }
        val low = percentile(total / 10)
        val high = percentile(total * 9 / 10)
        if (high - low < 18) return null
        val threshold = (low + high) / 2
        val result = StringBuilder(3)
        for (d in 0..2) {
            val x0 = left + d * digitW
            var mask = 0
            zones.forEachIndexed { index, z ->
                val sx = x0 + (z[0] * digitW).toInt()
                val sy = top + (z[1] * ch).toInt()
                val ex = x0 + max((z[2] * digitW).toInt(), (z[0] * digitW).toInt() + 1)
                val ey = top + max((z[3] * ch).toInt(), (z[1] * ch).toInt() + 1)
                var sum = 0L
                var count = 0
                for (y in sy until min(ey, bottom)) for (x in sx until min(ex, x0 + digitW)) {
                    sum += gray[y * width + x].toInt() and 0xff
                    count++
                }
                if (count > 0) {
                    val mean = sum.toFloat() / count
                    // Support dark-on-light and light-on-dark LCD/LED displays.
                    val on = mean < threshold
                    if (on) mask = mask or (1 shl index)
                }
            }
            result.append(digitByMask[mask] ?: return null)
        }
        return result.toString()
    }
}
