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
        0b1110111 to '0', 0b0100100 to '1', 0b1011101 to '2',
        0b1101101 to '3', 0b0101110 to '4', 0b1101011 to '5',
        0b1111011 to '6', 0b0100101 to '7', 0b1111111 to '8',
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
        val pixels = ArrayList<Int>(cw * ch)
        for (y in top until bottom) for (x in left until right) pixels.add(gray[y * width + x].toInt() and 0xff)
        pixels.sort()
        val low = pixels[pixels.size / 10]
        val high = pixels[pixels.size * 9 / 10]
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
                    val on = if (high - threshold > threshold - low) mean < threshold else mean > threshold
                    if (on) mask = mask or (1 shl (6 - index))
                }
            }
            result.append(digitByMask[mask] ?: return null)
        }
        return result.toString()
    }
}
