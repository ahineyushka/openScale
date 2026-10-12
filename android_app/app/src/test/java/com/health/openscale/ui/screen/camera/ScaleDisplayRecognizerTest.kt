/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.health.openscale.ui.screen.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScaleDisplayRecognizerTest {
    @Test
    fun findsSevenSegmentCandidateOnDarkBackground() {
        assertFindsEight(background = 12, segment = 240)
    }

    @Test
    fun findsSevenSegmentCandidateOnLightBackground() {
        assertFindsEight(background = 238, segment = 18)
    }

    @Test
    fun blankImageDoesNotProduceDigitCandidates() {
        val width = 180
        val height = 100
        val blank = ByteArray(width * height) { 120.toByte() }

        assertTrue(
            ScaleDisplayRecognizer.findDigitCandidates(blank, width, height).isEmpty()
        )
    }

    private fun assertFindsEight(background: Int, segment: Int) {
        val width = 220
        val height = 120
        val gray = ByteArray(width * height) { background.toByte() }
        val x = 82
        val y = 28
        val digitWidth = 30
        val digitHeight = 62

        // Segment geometry follows the recognizer's normalized A-G regions.
        val segments = listOf(
            floatArrayOf(.22f, .07f, .78f, .20f),
            floatArrayOf(.66f, .18f, .83f, .42f),
            floatArrayOf(.66f, .58f, .83f, .82f),
            floatArrayOf(.22f, .80f, .78f, .93f),
            floatArrayOf(.17f, .58f, .34f, .82f),
            floatArrayOf(.17f, .18f, .34f, .42f),
            floatArrayOf(.22f, .44f, .78f, .56f)
        )
        for (zone in segments) {
            val left = x + (zone[0] * digitWidth).toInt()
            val top = y + (zone[1] * digitHeight).toInt()
            val right = x + (zone[2] * digitWidth).toInt()
            val bottom = y + (zone[3] * digitHeight).toInt()
            for (py in top until bottom) {
                for (px in left until right) {
                    gray[py * width + px] = segment.toByte()
                }
            }
        }

        val candidates = ScaleDisplayRecognizer.findDigitCandidates(gray, width, height)
        assertFalse("Expected candidates over a clear digital glyph", candidates.isEmpty())
        val candidateSummary = candidates.take(12).joinToString { box ->
            box.digit.toString() + "@" + box.left + "," + box.top + "," +
                box.width + "x" + box.height + ":" + box.confidence
        }
        assertTrue(
            "Expected a candidate classified as 8 close to the drawn glyph; candidates=" + candidateSummary,
            candidates.any {
                it.digit == '8' &&
                    abs((it.left + it.right) / 2 - (x + digitWidth / 2)) <= 18 &&
                    abs((it.top + it.bottom) / 2 - (y + digitHeight / 2)) <= 18
            }
        )
    }
}
