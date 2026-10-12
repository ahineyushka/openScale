/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.health.openscale.ui.screen.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun findsAllTenDigitsOnBothDisplayPolarities() {
        assertFindsAllDigits(background = 12, segment = 240)
        assertFindsAllDigits(background = 240, segment = 15)
    }

    private fun assertFindsAllDigits(background: Int, segment: Int) {
        val width = 500
        val height = 100
        val gray = ByteArray(width * height) { background.toByte() }
        val digitWidth = 30
        val digitHeight = 62
        val y = 18
        val digitPositions = (0..9).associate { digit ->
            val x = 18 + digit * 48
            drawDigit(gray, width, x, y, digitWidth, digitHeight, ('0'.code + digit).toChar(), segment)
            ('0'.code + digit).toChar() to (x to y)
        }

        val candidates = ScaleDisplayRecognizer.findDigitCandidates(gray, width, height)
        for ((digit, position) in digitPositions) {
            assertTrue(
                "Missing digit $digit for polarity background=$background, segment=$segment. " +
                    "Candidates=" + candidates
                        .filter { abs((it.left + it.right) / 2 - (position.first + digitWidth / 2)) < 24 }
                        .joinToString { box ->
                            box.digit.toString() + "@" + box.left + "," + box.top + "," +
                                box.width + "x" + box.height + ":" + box.confidence
                        },
                candidates.any {
                    it.digit == digit &&
                        abs((it.left + it.right) / 2 - (position.first + digitWidth / 2)) <= 9 &&
                        abs((it.top + it.bottom) / 2 - (position.second + digitHeight / 2)) <= 9
                }
            )
        }
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

    @Test
    fun templateXxIgnoresUnmatchedRectanglesAndAllowsMissingLeadingDigit() {
        val candidates = listOf(
            box('1', 40, 40, 54, 70),
            box('2', 58, 40, 72, 70),
            box('3', 76, 40, 90, 70),
            box('4', 94, 40, 108, 70),
            // Small two-digit auxiliary readout: should be used only by a template with "xx".
            box('2', 112, 55, 120, 70),
            box('5', 122, 55, 130, 70),
            // Unrelated large character on another baseline.
            box('8', 175, 8, 189, 38, confidence = .99f)
        )

        val result = ScaleDisplayRecognizer.matchTemplate(candidates, "XXX.XX")

        assertNotNull(result)
        assertEquals(12.34, result!!.value, .0001)
        assertEquals("1234", result.digits)
        assertEquals(4, result.boxes.size)
        assertTrue(result.hasDecimal)
        assertEquals(2, result.decimalX.let { result.boxes.take(2).last().right.let { x -> if (it >= x) 2 else -1 } })
    }

    @Test
    fun templateXXXDotxxSelectsTwoSmallFractionDigits() {
        val candidates = listOf(
            box('1', 40, 40, 54, 70),
            box('2', 58, 40, 72, 70),
            box('3', 76, 40, 90, 70),
            box('4', 94, 55, 102, 70),
            box('5', 104, 55, 112, 70),
            box('8', 170, 5, 184, 35, confidence = .99f)
        )

        val result = ScaleDisplayRecognizer.matchTemplate(candidates, "XXX.xx")

        assertNotNull(result)
        assertEquals(123.45, result!!.value, .0001)
        assertEquals("12345", result.digits)
        assertEquals(5, result.boxes.size)
        assertTrue(result.hasDecimal)
    }

    @Test
    fun templateXXXDotXxSelectsOneSmallDigitAndIgnoresOtherSmallCandidate() {
        val candidates = listOf(
            box('1', 40, 40, 54, 70),
            box('2', 58, 40, 72, 70),
            box('3', 76, 40, 90, 70),
            box('4', 94, 55, 102, 70),
            box('5', 104, 55, 112, 70)
        )

        val result = ScaleDisplayRecognizer.matchTemplate(candidates, "XXX.Xx")

        assertNotNull(result)
        assertEquals(12.34, result!!.value, .0001)
        assertEquals("1234", result.digits)
        assertEquals(4, result.boxes.size)
        assertEquals('4', result.boxes.last().digit)
    }

    private fun box(
        digit: Char,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        confidence: Float = .92f
    ) = ScaleDisplayRecognizer.DigitBox(digit, left, top, right, bottom, confidence)

    @Test
    fun fullScanSelectsLargeWeightDigitsAndIgnoresSmallTemperatureDigits() {
        val width = 250
        val height = 130
        val gray = ByteArray(width * height) { 12.toByte() }
        val mainY = 32
        val mainWidth = 29
        val mainHeight = 58
        val mainXs = listOf(30, 64, 98, 132)
        val digits = listOf('1', '2', '3', '4')
        for (i in digits.indices) {
            drawDigit(gray, width, mainXs[i], mainY, mainWidth, mainHeight, digits[i], 240)
        }

        // Two small digits share the readout row but are the scale's temperature.
        drawDigit(gray, width, 171, mainY + mainHeight / 2, 14, 29, '2', 240)
        drawDigit(gray, width, 188, mainY + mainHeight / 2, 14, 29, '5', 240)

        val analysis = ScaleDisplayRecognizer.analyze(gray, width, height, "XXX.XX")
        val result = analysis.detection
        val candidateSummary = analysis.candidates
            .filter { it.height >= 20 }
            .sortedWith(compareBy({ it.top }, { it.left }))
            .take(80)
            .joinToString { box ->
                "${box.digit}@${box.left},${box.top},${box.width}x${box.height}:${"%.2f".format(java.util.Locale.ROOT, box.confidence)}"
            }

        assertNotNull(
            "Expected the combined detector and template matcher to find the weight. " +
                "Candidate count=${analysis.candidates.size}; candidates=$candidateSummary",
            result
        )
        assertEquals(12.34, result!!.value, .0001)
        assertEquals("1234", result.digits)
        assertEquals(4, result.boxes.size)
        assertTrue(result.hasDecimal)
        assertTrue(result.decimalX in result.boxes[1].right..result.boxes[2].left)
    }

    private fun drawDigit(
        gray: ByteArray,
        imageWidth: Int,
        x: Int,
        y: Int,
        digitWidth: Int,
        digitHeight: Int,
        digit: Char,
        foreground: Int
    ) {
        val masks = intArrayOf(
            0b0111111, 0b0000110, 0b1011011, 0b1001111, 0b1100110,
            0b1101101, 0b1111101, 0b0000111, 0b1111111, 0b1101111
        )
        val zones = listOf(
            floatArrayOf(.22f, .07f, .78f, .20f),
            floatArrayOf(.66f, .18f, .83f, .42f),
            floatArrayOf(.66f, .58f, .83f, .82f),
            floatArrayOf(.22f, .80f, .78f, .93f),
            floatArrayOf(.17f, .58f, .34f, .82f),
            floatArrayOf(.17f, .18f, .34f, .42f),
            floatArrayOf(.22f, .44f, .78f, .56f)
        )
        val mask = masks[digit - '0']
        for (index in zones.indices) {
            if ((mask and (1 shl index)) == 0) continue
            val zone = zones[index]
            val left = x + (zone[0] * digitWidth).toInt()
            val top = y + (zone[1] * digitHeight).toInt()
            val right = x + (zone[2] * digitWidth).toInt()
            val bottom = y + (zone[3] * digitHeight).toInt()
            for (py in top until bottom) {
                for (px in left until right) {
                    gray[py * imageWidth + px] = foreground.toByte()
                }
            }
        }
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
