/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.health.openscale.ui.screen.camera

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lightweight seven-segment detector and classifier.
 *
 * It searches for individual character cells at several sizes across the whole image.
 * Each candidate is scored against seven-segment masks using local foreground/background
 * contrast, trying both light-on-dark and dark-on-light displays. Candidate cells are then
 * grouped by height and baseline. The user-selected format decides which size groups are
 * significant; the detector does not try to identify temperature or other display labels.
 *
 * This is a heuristic prototype. It deliberately rejects weak/incomplete matches instead of
 * inventing a reading, and still needs validation against a broad set of real scale photos.
 */
internal object ScaleDisplayRecognizer {
    private val digitMasks = intArrayOf(
        0b0111111, // 0
        0b0000110, // 1
        0b1011011, // 2
        0b1001111, // 3
        0b1100110, // 4
        0b1101101, // 5
        0b1111101, // 6
        0b0000111, // 7
        0b1111111, // 8
        0b1101111  // 9
    )
    private val digitChars = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')

    // A, B, C, D, E, F, G sample regions inside a candidate digit cell.
    private val segmentZones = arrayOf(
        floatArrayOf(.22f, .07f, .78f, .20f),
        floatArrayOf(.66f, .18f, .83f, .42f),
        floatArrayOf(.66f, .58f, .83f, .82f),
        floatArrayOf(.22f, .80f, .78f, .93f),
        floatArrayOf(.17f, .58f, .34f, .82f),
        floatArrayOf(.17f, .18f, .34f, .42f),
        floatArrayOf(.22f, .44f, .78f, .56f)
    )

    // These corner samples estimate the unlit background inside the same character cell.
    private val backgroundZones = arrayOf(
        floatArrayOf(.04f, .26f, .12f, .39f),
        floatArrayOf(.88f, .26f, .96f, .39f),
        floatArrayOf(.04f, .62f, .12f, .75f),
        floatArrayOf(.88f, .62f, .96f, .75f)
    )

    // The open spaces between horizontal bars should look like the background. This rejects
    // solid icons, printed stars and other shapes that accidentally light up seven sample zones.
    private val gapZones = arrayOf(
        floatArrayOf(.42f, .23f, .58f, .37f),
        floatArrayOf(.42f, .63f, .58f, .77f)
    )

    data class DigitBox(
        val digit: Char,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val confidence: Float
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    data class Detection(
        val digits: String,
        val value: Double,
        val boxes: List<DigitBox>,
        val decimalX: Int,
        val decimalY: Int,
        val hasDecimal: Boolean,
        val confidence: Float
    )

    private data class DigitRow(
        val boxes: List<DigitBox>,
        val height: Float,
        val confidence: Float,
        val score: Float
    )

    /**
     * Returns the number of decimal digits in an X/x format template.
     * Uppercase X and lowercase x are both numeric positions.
     */
    fun decimalPlaces(format: String): Int =
        format.substringAfter('.', "").count { it == 'X' || it == 'x' }

    /**
     * Finds characters and applies the user-selected template.
     *
     * maxSide limits work for live camera frames. Larger photos can use a larger value
     * to preserve details of small digits.
     */
    fun find(
        gray: ByteArray,
        width: Int,
        height: Int,
        format: String,
        maxSide: Int = 420
    ): Detection? {
        if (width < 40 || height < 40 || gray.size < width * height) return null
        val longestSide = max(width, height)
        val resize = longestSide.toFloat() / maxSide.coerceAtLeast(160)
        val smallWidth = if (resize > 1f) (width / resize).roundToInt().coerceAtLeast(1) else width
        val smallHeight = if (resize > 1f) (height / resize).roundToInt().coerceAtLeast(1) else height
        val smallGray = if (smallWidth == width && smallHeight == height) {
            gray.copyOf(width * height)
        } else {
            ByteArray(smallWidth * smallHeight).also { out ->
                for (y in 0 until smallHeight) {
                    val sourceY = (y.toFloat() * height / smallHeight).toInt().coerceAtMost(height - 1)
                    for (x in 0 until smallWidth) {
                        val sourceX = (x.toFloat() * width / smallWidth).toInt().coerceAtMost(width - 1)
                        out[y * smallWidth + x] = gray[sourceY * width + sourceX]
                    }
                }
            }
        }
        val integral = buildIntegral(smallGray, smallWidth, smallHeight)
        val candidates = findCandidates(integral, smallWidth, smallHeight)
        if (candidates.isEmpty()) return null
        val rows = findRows(candidates)
        if (rows.isEmpty()) return null
        val chosen = chooseReading(rows, candidates, format) ?: return null

        val scaleX = width.toFloat() / smallWidth
        val scaleY = height.toFloat() / smallHeight
        val scaledBoxes = chosen.boxes.map { box ->
            DigitBox(
                box.digit,
                (box.left * scaleX).roundToInt().coerceIn(0, width - 1),
                (box.top * scaleY).roundToInt().coerceIn(0, height - 1),
                (box.right * scaleX).roundToInt().coerceIn(1, width),
                (box.bottom * scaleY).roundToInt().coerceIn(1, height),
                box.confidence
            )
        }
        return Detection(
            chosen.digits,
            chosen.value,
            scaledBoxes,
            if (chosen.hasDecimal) (chosen.decimalX * scaleX).roundToInt().coerceIn(0, width - 1) else -1,
            if (chosen.hasDecimal) (chosen.decimalY * scaleY).roundToInt().coerceIn(0, height - 1) else -1,
            chosen.hasDecimal,
            chosen.confidence
        )
    }

    private data class ChosenReading(
        val digits: String,
        val value: Double,
        val boxes: List<DigitBox>,
        val decimalX: Int,
        val decimalY: Int,
        val hasDecimal: Boolean,
        val confidence: Float
    )

    private fun chooseReading(
        rows: List<DigitRow>,
        candidates: List<DigitBox>,
        format: String
    ): ChosenReading? {
        val upperSlots = format.count { it == 'X' }
        val smallSlots = format.count { it == 'x' }
        val decimals = decimalPlaces(format)
        if (upperSlots == 0 && smallSlots == 0) return null

        val minimumMainDigits = max(1, decimals - smallSlots + 1)
        val compatibleRows = rows.filter { row ->
            row.boxes.size in minimumMainDigits..upperSlots
        }.sortedByDescending { it.score }
        val primary = compatibleRows.firstOrNull() ?: return null
        val mainBoxes = primary.boxes.sortedBy { it.left }
        var smallBoxes = emptyList<DigitBox>()

        if (smallSlots > 0) {
            val rightEdge = mainBoxes.lastOrNull()?.right ?: return null
            val baseline = mainBoxes.map { it.bottom }.average().toFloat()
            val referenceHeight = mainBoxes.map { it.height }.average().toFloat()
            val smallCandidates = candidates.filter { box ->
                val ratio = box.height / referenceHeight
                ratio in .28f.. .82f &&
                    box.left >= rightEdge - referenceHeight * .12f &&
                    box.left - rightEdge <= referenceHeight * .70f &&
                    abs(box.bottom - baseline) <= referenceHeight * .28f
            }
            val smallRows = findRows(smallCandidates)
                .filter { row ->
                    row.boxes.size >= smallSlots &&
                        row.height in (referenceHeight * .28f)..(referenceHeight * .82f) &&
                        row.boxes.first().left - rightEdge <= referenceHeight * .70f
                }
                .sortedBy { row ->
                    val gap = max(0, row.boxes.first().left - rightEdge)
                    val sizePenalty = abs(row.height / referenceHeight - .55f)
                    gap.toFloat() / referenceHeight + sizePenalty * .35f - row.confidence * .15f
                }
            val smallRow = smallRows.firstOrNull() ?: return null
            smallBoxes = smallRow.boxes.sortedBy { it.left }.take(smallSlots)
        }
        if (mainBoxes.size > upperSlots || smallBoxes.size < smallSlots) return null
        val selected = (mainBoxes + smallBoxes).distinctBy {
            ((it.left + it.right) / 2) / 3 to ((it.top + it.bottom) / 2) / 3
        }.sortedBy { it.left }
        if (selected.isEmpty() || selected.size < decimals) return null

        val digitString = selected.joinToString("") { it.digit.toString() }
        val integerValue = digitString.toLongOrNull() ?: return null
        var divisor = 1.0
        repeat(decimals) { divisor *= 10.0 }
        val value = integerValue.toDouble() / divisor
        val hasDecimal = format.contains('.')
        val boundaryIndex = selected.size - decimals
        val decimalX = when {
            !hasDecimal -> -1
            decimals == 0 -> selected.last().right + max(2, selected.last().width / 5)
            boundaryIndex <= 0 -> (selected.first().left - max(2, selected.first().width / 5)).coerceAtLeast(0)
            boundaryIndex >= selected.size -> selected.last().right + max(2, selected.last().width / 5)
            else -> (selected[boundaryIndex - 1].right + selected[boundaryIndex].left) / 2
        }
        val largeBoxes = if (mainBoxes.isNotEmpty()) mainBoxes else selected
        val baseline = largeBoxes.map { it.bottom }.average().toInt()
        val averageHeight = largeBoxes.map { it.height }.average().toInt().coerceAtLeast(1)
        val decimalY = (baseline - max(1, averageHeight / 12)).coerceAtLeast(0)
        return ChosenReading(
            digitString,
            value,
            selected,
            decimalX,
            decimalY,
            hasDecimal,
            selected.map { it.confidence }.average().toFloat()
        )
    }

    private fun findCandidates(
        integral: IntArray,
        width: Int,
        height: Int
    ): List<DigitBox> {
        val candidates = ArrayList<DigitBox>()
        val segmentMeans = FloatArray(13)
        val digitOut = CharArray(1)
        val ratios = floatArrayOf(.32f, .37f, .42f, .47f, .52f)
        val minHeight = max(9, min(width, height) / 50)
        val maxHeight = min((height * .72f).toInt(), (width * .65f).toInt()).coerceAtLeast(minHeight)
        var digitHeight = minHeight

        while (digitHeight <= maxHeight) {
            // Coarse scanning plus score-based suppression keeps the whole-frame search
            // tractable for live camera frames without assuming a fixed display location.
            val stepX = max(2, digitHeight / 4)
            val stepY = max(2, digitHeight / 4)
            for (ratio in ratios) {
                val digitWidth = max(8, (digitHeight * ratio).roundToInt())
                if (digitWidth > width || digitHeight > height) continue
                var y = 0
                while (y + digitHeight <= height) {
                    var x = 0
                    while (x + digitWidth <= width) {
                        val confidence = scoreCandidate(
                            integral, width, height, x, y, digitWidth, digitHeight,
                            segmentMeans, digitOut
                        )
                        if (confidence >= .38f) {
                            candidates.add(
                                DigitBox(
                                    digitOut[0], x, y, x + digitWidth, y + digitHeight, confidence
                                )
                            )
                        }
                        x += stepX
                    }
                    y += stepY
                }
            }
            digitHeight = max(digitHeight + 2, (digitHeight * 1.16f).roundToInt())
            if (candidates.size > 3500) {
                val pruned = suppressCandidates(candidates, 700)
                candidates.clear()
                candidates.addAll(pruned)
            }
        }
        return suppressCandidates(candidates, 360)
    }

    private fun scoreCandidate(
        integral: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        digitWidth: Int,
        digitHeight: Int,
        samples: FloatArray,
        digitOut: CharArray
    ): Float {
        for (i in segmentZones.indices) {
            val z = segmentZones[i]
            samples[i] = mean(
                integral, width, height,
                x + (z[0] * digitWidth).toInt(),
                y + (z[1] * digitHeight).toInt(),
                x + max((z[2] * digitWidth).toInt(), (z[0] * digitWidth).toInt() + 1),
                y + max((z[3] * digitHeight).toInt(), (z[1] * digitHeight).toInt() + 1)
            )
        }
        for (i in backgroundZones.indices) {
            val z = backgroundZones[i]
            samples[7 + i] = mean(
                integral, width, height,
                x + (z[0] * digitWidth).toInt(),
                y + (z[1] * digitHeight).toInt(),
                x + max((z[2] * digitWidth).toInt(), (z[0] * digitWidth).toInt() + 1),
                y + max((z[3] * digitHeight).toInt(), (z[1] * digitHeight).toInt() + 1)
            )
        }
        for (i in gapZones.indices) {
            val z = gapZones[i]
            samples[11 + i] = mean(
                integral, width, height,
                x + (z[0] * digitWidth).toInt(),
                y + (z[1] * digitHeight).toInt(),
                x + max((z[2] * digitWidth).toInt(), (z[0] * digitWidth).toInt() + 1),
                y + max((z[3] * digitHeight).toInt(), (z[1] * digitHeight).toInt() + 1)
            )
        }
        val background = (samples[7] + samples[8] + samples[9] + samples[10]) * .25f
        val gapDeviation = abs((samples[11] + samples[12]) * .5f - background)
        for (i in 0..6) samples[i] -= background

        var best = 0f
        var bestDigit = '?'
        for (polarity in intArrayOf(1, -1)) {
            for (digitIndex in digitMasks.indices) {
                val mask = digitMasks[digitIndex]
                val activeCount = Integer.bitCount(mask)
                var activeSum = 0f
                for (segment in 0..6) {
                    if ((mask and (1 shl segment)) != 0) {
                        activeSum += samples[segment] * polarity
                    }
                }
                val activeMean = activeSum / activeCount
                if (activeMean < 7f) continue

                var activeError = 0f
                var inactiveEnergy = 0f
                var inactiveCount = 0
                for (segment in 0..6) {
                    val signal = samples[segment] * polarity
                    if ((mask and (1 shl segment)) != 0) {
                        val delta = signal - activeMean
                        activeError += delta * delta
                    } else {
                        inactiveEnergy += signal * signal
                        inactiveCount++
                    }
                }
                val activeDeviation = sqrt(activeError / activeCount.coerceAtLeast(1))
                val inactiveDeviation = if (inactiveCount > 0) sqrt(inactiveEnergy / inactiveCount) else 0f
                val activeFit = (1f - activeDeviation / max(1f, activeMean * .85f)).coerceIn(0f, 1f)
                val inactiveFit = (1f - inactiveDeviation / max(1f, activeMean * .60f)).coerceIn(0f, 1f)
                val contrast = ((activeMean - 7f) / 30f).coerceIn(0f, 1f)
                val gapFit = (1f - gapDeviation / max(1f, activeMean * .80f)).coerceIn(0f, 1f)
                val score = activeFit * inactiveFit * contrast * gapFit
                if (score > best) {
                    best = score
                    bestDigit = digitChars[digitIndex]
                }
            }
        }
        digitOut[0] = bestDigit
        return best
    }

    private fun findRows(candidates: List<DigitBox>): List<DigitRow> {
        if (candidates.isEmpty()) return emptyList()
        val result = ArrayList<DigitRow>()
        val seen = HashSet<String>()
        for (seed in candidates) {
            val seedHeight = seed.height.toFloat()
            val aligned = candidates.filter { box ->
                abs(box.height - seed.height) <= max(2f, seedHeight * .23f) &&
                    abs(box.bottom - seed.bottom) <= max(3f, seedHeight * .23f)
            }.sortedBy { it.left }
            if (aligned.isEmpty()) continue

            var chain = ArrayList<DigitBox>()
            fun flush() {
                if (chain.size >= 2) {
                    val sorted = chain.sortedBy { it.left }
                    for (from in sorted.indices) {
                        val maxTo = min(sorted.lastIndex, from + 6)
                        for (to in from + 1..maxTo) {
                            val sequence = sorted.subList(from, to + 1).toList()
                            val averageHeight = sequence.map { it.height }.average().toFloat()
                            if (!plausibleRow(sequence, averageHeight)) continue
                            val key = sequence.joinToString(",") {
                                (it.left / 4).toString() + ":" +
                                    (it.top / 4).toString() + ":" +
                                    (it.width / 4).toString() + ":" +
                                    (it.height / 4).toString()
                            }
                            if (!seen.add(key)) continue
                            val averageConfidence = sequence.map { it.confidence }.average().toFloat()
                            val pitchScore = rowPitchScore(sequence)
                            val sizeScore = sequence.map { it.width.toFloat() / it.height }.average().toFloat()
                            val widthPrior = (1f - abs(sizeScore - .43f) / .40f).coerceIn(0f, 1f)
                            val score = averageConfidence * 2f +
                                min(sequence.size, 5) * .24f +
                                pitchScore * .65f +
                                widthPrior * .20f +
                                ln(averageHeight.coerceAtLeast(1f)) * .25f
                            result.add(DigitRow(sequence, averageHeight, averageConfidence, score))
                        }
                    }
                }
                chain = ArrayList()
            }
            for (box in aligned) {
                if (chain.isEmpty()) {
                    chain.add(box)
                    continue
                }
                val previous = chain.last()
                val gap = box.left - previous.right
                val localHeight = min(previous.height, box.height).toFloat()
                val gapLimit = max(3f, localHeight * .45f)
                if (gap <= gapLimit && gap >= -localHeight * .08f) {
                    chain.add(box)
                } else {
                    flush()
                    chain.add(box)
                }
            }
            flush()
        }
        return result
    }

    private fun plausibleRow(sequence: List<DigitBox>, averageHeight: Float): Boolean {
        if (sequence.size < 2 || averageHeight < 8f) return false
        val bottom = sequence.map { it.bottom }.average().toFloat()
        if (sequence.any {
                abs(it.height - averageHeight) > max(2f, averageHeight * .20f) ||
                    abs(it.bottom - bottom) > max(2f, averageHeight * .18f)
            }) return false
        if (sequence.any { (it.width.toFloat() / it.height) !in .24f.. .70f }) return false
        val pitches = sequence.zipWithNext { left, right ->
            (right.left + right.right) / 2f - (left.left + left.right) / 2f
        }
        if (pitches.any { it !in (averageHeight * .34f)..(averageHeight * 1.05f) }) return false
        val meanPitch = pitches.average().toFloat()
        val pitchDeviation = sqrt(pitches.map { (it - meanPitch) * (it - meanPitch) }.average()).toFloat()
        return meanPitch > 0f && pitchDeviation / meanPitch < .38f
    }

    private fun rowPitchScore(sequence: List<DigitBox>): Float {
        if (sequence.size < 2) return 0f
        val pitches = sequence.zipWithNext { left, right ->
            (right.left + right.right) / 2f - (left.left + left.right) / 2f
        }
        val meanPitch = pitches.average().toFloat()
        if (meanPitch <= 0f) return 0f
        val deviation = sqrt(pitches.map { (it - meanPitch) * (it - meanPitch) }.average()).toFloat()
        return (1f - deviation / meanPitch).coerceIn(0f, 1f)
    }

    private fun suppressCandidates(candidates: List<DigitBox>, limit: Int): List<DigitBox> {
        if (candidates.isEmpty()) return emptyList()
        val ordered = candidates.sortedByDescending { it.confidence }
        val kept = ArrayList<DigitBox>()
        for (candidate in ordered) {
            val duplicate = kept.any { previous ->
                val heightRatio = max(candidate.height, previous.height).toFloat() /
                    min(candidate.height, previous.height).coerceAtLeast(1)
                val dx = abs((candidate.left + candidate.right) - (previous.left + previous.right)) / 2f
                val dy = abs((candidate.top + candidate.bottom) - (previous.top + previous.bottom)) / 2f
                heightRatio < 1.38f &&
                    dx < max(4f, min(candidate.height, previous.height) * .46f) &&
                    dy < max(3f, min(candidate.height, previous.height) * .28f)
            }
            if (!duplicate) kept.add(candidate)
            if (kept.size >= limit) break
        }
        return kept
    }

    private fun buildIntegral(gray: ByteArray, width: Int, height: Int): IntArray {
        val stride = width + 1
        val integral = IntArray((width + 1) * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0
            val srcRow = y * width
            val dstRow = (y + 1) * stride
            val previousRow = y * stride
            for (x in 0 until width) {
                rowSum += gray[srcRow + x].toInt() and 0xff
                integral[dstRow + x + 1] = integral[previousRow + x + 1] + rowSum
            }
        }
        return integral
    }

    private fun mean(
        integral: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Float {
        val x0 = left.coerceIn(0, width)
        val y0 = top.coerceIn(0, height)
        val x1 = right.coerceIn(x0 + 1, width)
        val y1 = bottom.coerceIn(y0 + 1, height)
        val stride = width + 1
        val sum = integral[y1 * stride + x1] - integral[y0 * stride + x1] -
            integral[y1 * stride + x0] + integral[y0 * stride + x0]
        return sum.toFloat() / ((x1 - x0) * (y1 - y0)).coerceAtLeast(1)
    }
}
