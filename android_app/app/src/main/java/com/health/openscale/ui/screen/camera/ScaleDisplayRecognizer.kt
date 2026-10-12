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

    // Wide corner samples estimate the background without landing on active side segments.
    private val backgroundZones = arrayOf(
        floatArrayOf(.02f, .02f, .18f, .13f),
        floatArrayOf(.82f, .02f, .98f, .13f),
        floatArrayOf(.02f, .87f, .18f, .98f),
        floatArrayOf(.82f, .87f, .98f, .98f)
    )

    // Three samples along each segment reject isolated bright/dark marks that only hit one point.
    private val segmentSampleZones: Array<FloatArray> = Array(21) { index ->
        val segment = index / 3
        val position = floatArrayOf(.28f, .50f, .72f)[index % 3]
        val zone = segmentZones[segment]
        if (segment == 0 || segment == 3 || segment == 6) {
            floatArrayOf(
                zone[0] + (zone[2] - zone[0]) * (position - .10f),
                zone[1] + (zone[3] - zone[1]) * .25f,
                zone[0] + (zone[2] - zone[0]) * (position + .10f),
                zone[3] - (zone[3] - zone[1]) * .25f
            )
        } else {
            floatArrayOf(
                zone[0] + (zone[2] - zone[0]) * .25f,
                zone[1] + (zone[3] - zone[1]) * (position - .10f),
                zone[2] - (zone[2] - zone[0]) * .25f,
                zone[1] + (zone[3] - zone[1]) * (position + .10f)
            )
        }
    }

    // Broad regions used only to tighten the returned rectangle around lit/active strokes.
    private val segmentBounds = arrayOf(
        floatArrayOf(.13f, .02f, .87f, .24f),
        floatArrayOf(.60f, .12f, .88f, .48f),
        floatArrayOf(.60f, .52f, .88f, .88f),
        floatArrayOf(.13f, .76f, .87f, .98f),
        floatArrayOf(.12f, .52f, .40f, .88f),
        floatArrayOf(.12f, .12f, .40f, .48f),
        floatArrayOf(.13f, .40f, .87f, .60f)
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

    /** Raw candidates and the best sequence selected by the user template, from one scan. */
    data class Analysis(val candidates: List<DigitBox>, val detection: Detection?)

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
     * Returns the detector's raw single-character candidates without trying to interpret
     * them as a complete weight. False positives are expected at this stage; later stages
     * use the selected template and sequence geometry to rank and reject them.
     */
    fun findDigitCandidates(
        gray: ByteArray,
        width: Int,
        height: Int,
        maxSide: Int = 800
    ): List<DigitBox> {
        if (width < 40 || height < 40 || gray.size < width * height) return emptyList()
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
        val candidates = findCandidates(buildIntegral(smallGray, smallWidth, smallHeight), smallWidth, smallHeight)
        val scaleX = width.toFloat() / smallWidth
        val scaleY = height.toFloat() / smallHeight
        return candidates.map { box ->
            DigitBox(
                box.digit,
                (box.left * scaleX).roundToInt().coerceIn(0, width - 1),
                (box.top * scaleY).roundToInt().coerceIn(0, height - 1),
                (box.right * scaleX).roundToInt().coerceIn(1, width),
                (box.bottom * scaleY).roundToInt().coerceIn(1, height),
                box.confidence
            )
        }
    }

    /**
     * Finds characters and applies the user-selected template.
     *
     * maxSide limits work for live camera frames. Still photos keep more pixels by default
     * because small LCD segments lose contrast quickly when heavily downsampled.
     */
    fun find(
        gray: ByteArray,
        width: Int,
        height: Int,
        format: String,
        maxSide: Int = 800
    ): Detection? = analyze(gray, width, height, format, maxSide).detection

    fun analyze(
        gray: ByteArray,
        width: Int,
        height: Int,
        format: String,
        maxSide: Int = 800
    ): Analysis {
        if (width < 40 || height < 40 || gray.size < width * height) return Analysis(emptyList(), null)
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
        val scaleX = width.toFloat() / smallWidth
        val scaleY = height.toFloat() / smallHeight
        val scaledCandidates = candidates.map { box ->
            DigitBox(
                box.digit,
                (box.left * scaleX).roundToInt().coerceIn(0, width - 1),
                (box.top * scaleY).roundToInt().coerceIn(0, height - 1),
                (box.right * scaleX).roundToInt().coerceIn(1, width),
                (box.bottom * scaleY).roundToInt().coerceIn(1, height),
                box.confidence
            )
        }
        if (candidates.isEmpty()) return Analysis(emptyList(), null)
        val rows = findRows(candidates)
        if (rows.isEmpty()) return Analysis(scaledCandidates, null)
        val chosen = chooseReading(rows, candidates, format) ?: return Analysis(scaledCandidates, null)
        val refinedBoxes = chosen.boxes.map { refineBox(integral, smallGray, smallWidth, smallHeight, it) }
            .sortedBy { it.left }
        val scaledBoxes = refinedBoxes.map { box ->
            DigitBox(
                box.digit,
                (box.left * scaleX).roundToInt().coerceIn(0, width - 1),
                (box.top * scaleY).roundToInt().coerceIn(0, height - 1),
                (box.right * scaleX).roundToInt().coerceIn(1, width),
                (box.bottom * scaleY).roundToInt().coerceIn(1, height),
                box.confidence
            )
        }
        val decimals = decimalPlaces(format)
        val boundaryIndex = scaledBoxes.size - decimals
        val decimalX = when {
            !chosen.hasDecimal -> -1
            decimals == 0 -> scaledBoxes.last().right + max(2, scaledBoxes.last().width / 5)
            boundaryIndex <= 0 -> (scaledBoxes.first().left - max(2, scaledBoxes.first().width / 5)).coerceAtLeast(0)
            boundaryIndex >= scaledBoxes.size -> scaledBoxes.last().right + max(2, scaledBoxes.last().width / 5)
            else -> (scaledBoxes[boundaryIndex - 1].right + scaledBoxes[boundaryIndex].left) / 2
        }.coerceIn(if (chosen.hasDecimal) 0 else -1, width - 1)
        val largeHeight = scaledBoxes.maxOfOrNull { it.height } ?: 1
        val mainBaseline = scaledBoxes.filter { it.height >= largeHeight * .80f }
            .map { it.bottom }.average().toInt()
        val decimalY = (mainBaseline - max(1, largeHeight / 12)).coerceAtLeast(0)
        return Analysis(
            scaledCandidates,
            Detection(
                chosen.digits,
                chosen.value,
                scaledBoxes,
                decimalX,
                if (chosen.hasDecimal) decimalY else -1,
                chosen.hasDecimal,
                chosen.confidence
            )
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

    private fun refineBox(
        integral: IntArray,
        gray: ByteArray,
        width: Int,
        height: Int,
        box: DigitBox
    ): DigitBox {
        val x = box.left.coerceIn(0, width - 1)
        val y = box.top.coerceIn(0, height - 1)
        val w = box.width.coerceAtLeast(1)
        val h = box.height.coerceAtLeast(1)
        if (w < 7 || h < 10) return box
        val background = backgroundZones.map { z ->
            mean(
                integral, width, height,
                x + (z[0] * w).toInt(),
                y + (z[1] * h).toInt(),
                x + max((z[2] * w).toInt(), (z[0] * w).toInt() + 1),
                y + max((z[3] * h).toInt(), (z[1] * h).toInt() + 1)
            )
        }.average().toFloat()
        val mask = digitMasks[box.digit - '0']
        var activeDifference = 0f
        var activeCount = 0
        for (i in segmentZones.indices) {
            if ((mask and (1 shl i)) == 0) continue
            val z = segmentZones[i]
            val value = mean(
                integral, width, height,
                x + (z[0] * w).toInt(),
                y + (z[1] * h).toInt(),
                x + max((z[2] * w).toInt(), (z[0] * w).toInt() + 1),
                y + max((z[3] * h).toInt(), (z[1] * h).toInt() + 1)
            )
            activeDifference += value - background
            activeCount++
        }
        if (activeCount == 0) return box
        val polarity = if (activeDifference >= 0f) 1 else -1
        val magnitude = abs(activeDifference / activeCount)
        val threshold = max(16f, magnitude * .28f)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (segment in segmentBounds.indices) {
            if ((mask and (1 shl segment)) == 0) continue
            val z = segmentBounds[segment]
            val x1 = (x + (z[0] * w).toInt()).coerceIn(0, width - 1)
            val y1 = (y + (z[1] * h).toInt()).coerceIn(0, height - 1)
            val x2 = (x + max((z[2] * w).toInt(), (z[0] * w).toInt() + 1)).coerceIn(x1 + 1, width)
            val y2 = (y + max((z[3] * h).toInt(), (z[1] * h).toInt() + 1)).coerceIn(y1 + 1, height)
            for (py in y1 until y2) {
                for (px in x1 until x2) {
                    val pixel = gray[py * width + px].toInt() and 0xff
                    if ((pixel - background) * polarity >= threshold) {
                        minX = min(minX, px)
                        minY = min(minY, py)
                        maxX = max(maxX, px)
                        maxY = max(maxY, py)
                    }
                }
            }
        }
        if (maxX < minX || maxY < minY) return box
        val paddingX = max(1, w / 35)
        val paddingY = max(1, h / 45)
        return box.copy(
            left = (minX - paddingX).coerceAtLeast(0),
            top = (minY - paddingY).coerceAtLeast(0),
            right = (maxX + paddingX + 1).coerceAtMost(width),
            bottom = (maxY + paddingY + 1).coerceAtMost(height)
        )
    }

    private data class TemplateSpec(
        val integerSlots: Int,
        val largeFractionSlots: Int,
        val smallFractionSlots: Int,
        val decimalPlaces: Int,
        val hasDecimal: Boolean
    ) {
        val minLargeDigits: Int get() = largeFractionSlots + 1
        val maxLargeDigits: Int get() = integerSlots + largeFractionSlots
    }

    private data class RankedReading(
        val reading: ChosenReading,
        val score: Float
    )

    /**
     * Matches a selected format against candidate rectangles only.
     *
     * Any candidates not part of the best matching sequence are ignored. Missing leading
     * integer positions are allowed; the fractional positions and their selected sizes are not.
     */
    internal fun matchTemplate(
        candidates: List<DigitBox>,
        format: String
    ): Detection? {
        val rows = findRows(candidates)
        val chosen = chooseReading(rows, candidates, format) ?: return null
        return Detection(
            chosen.digits,
            chosen.value,
            chosen.boxes.sortedBy { it.left },
            chosen.decimalX,
            chosen.decimalY,
            chosen.hasDecimal,
            chosen.confidence
        )
    }

    private fun parseTemplate(format: String): TemplateSpec? {
        val point = format.indexOf('.')
        if (point >= 0 && format.indexOf('.', point + 1) >= 0) return null
        val integerPart = if (point < 0) format else format.substring(0, point)
        val fractionPart = if (point < 0) "" else format.substring(point + 1)
        if (integerPart.isEmpty() || integerPart.any { it != 'X' }) return null
        if (fractionPart.any { it != 'X' && it != 'x' }) return null
        if (point < 0 && format.any { it != 'X' }) return null

        return TemplateSpec(
            integerSlots = integerPart.length,
            largeFractionSlots = fractionPart.count { it == 'X' },
            smallFractionSlots = fractionPart.count { it == 'x' },
            decimalPlaces = fractionPart.length,
            hasDecimal = point >= 0
        )
    }

    private fun chooseReading(
        rows: List<DigitRow>,
        candidates: List<DigitBox>,
        format: String
    ): ChosenReading? {
        val template = parseTemplate(format) ?: return null
        if (rows.isEmpty()) return null
        var best: RankedReading? = null

        for (row in rows) {
            val sorted = row.boxes.sortedBy { it.left }
            val minLength = template.minLargeDigits
            val maxLength = min(template.maxLargeDigits, sorted.size)
            if (minLength > maxLength) continue

            for (length in minLength..maxLength) {
                for (start in 0..(sorted.size - length)) {
                    val mainBoxes = sorted.subList(start, start + length).toList()
                    val mainGeometry = sequenceGeometryScore(mainBoxes)
                    if (mainGeometry <= 0f) continue
                    val mainConfidence = mainBoxes.map { it.confidence }.average().toFloat()
                    val lengthFit = length.toFloat() / template.maxLargeDigits.coerceAtLeast(1)
                    val mainScore = mainConfidence * .62f + lengthFit * .26f + mainGeometry * .12f

                    if (template.smallFractionSlots == 0) {
                        val candidate = buildReading(mainBoxes, emptyList(), template) ?: continue
                        val score = mainScore
                        if (best == null || score > best.score) best = RankedReading(candidate, score)
                        continue
                    }

                    val referenceHeight = mainBoxes.map { it.height }.average().toFloat()
                    if (referenceHeight <= 0f) continue
                    val rightEdge = mainBoxes.last().right
                    val baseline = mainBoxes.map { it.bottom }.average().toFloat()
                    // Rows are already grouped once from all candidates. Reuse those groups here
                    // rather than re-running the O(n²) grouping pass for every main-row hypothesis.
                    val smallRows = rows.filter { smallRow ->
                        val first = smallRow.boxes.firstOrNull() ?: return@filter false
                        val ratio = smallRow.height / referenceHeight
                        val gap = first.left - rightEdge
                        val smallBaseline = smallRow.boxes.map { it.bottom }.average().toFloat()
                        ratio in .28f.. .82f &&
                            gap >= -referenceHeight * .12f &&
                            gap <= referenceHeight * .72f &&
                            abs(smallBaseline - baseline) <= referenceHeight * .32f
                    }
                    for (smallRow in smallRows) {
                        val smallSorted = smallRow.boxes.sortedBy { it.left }
                        val required = template.smallFractionSlots
                        if (smallSorted.size < required) continue
                        for (smallStart in 0..(smallSorted.size - required)) {
                            val smallBoxes = smallSorted.subList(smallStart, smallStart + required).toList()
                            if (sequenceGeometryScore(smallBoxes) <= 0f) continue
                            val firstGap = smallBoxes.first().left - rightEdge
                            if (firstGap < -referenceHeight * .12f ||
                                firstGap > referenceHeight * .72f
                            ) continue

                            val gapRatio = firstGap / referenceHeight
                            val gapFit = (1f - abs(gapRatio - .12f) / .85f).coerceIn(0f, 1f)
                            val smallConfidence = smallBoxes.map { it.confidence }.average().toFloat()
                            val smallGeometry = sequenceGeometryScore(smallBoxes)
                            val score = mainScore * .68f +
                                smallConfidence * .18f +
                                smallGeometry * .08f +
                                gapFit * .06f
                            val candidate = buildReading(mainBoxes, smallBoxes, template) ?: continue
                            if (best == null || score > best.score) best = RankedReading(candidate, score)
                        }
                    }
                }
            }
        }
        return best?.reading
    }

    /**
     * Scores the spacing and alignment within one candidate sequence. Singleton sequences are
     * valid because a small weight can have only one visible integer digit.
     */
    private fun sequenceGeometryScore(boxes: List<DigitBox>): Float {
        if (boxes.isEmpty()) return 0f
        if (boxes.size == 1) return 1f
        val averageHeight = boxes.map { it.height }.average().toFloat()
        if (averageHeight <= 0f) return 0f
        if (boxes.any { it.height / averageHeight !in .74f..1.26f }) return 0f
        val baseline = boxes.map { it.bottom }.average().toFloat()
        if (boxes.any { abs(it.bottom - baseline) > averageHeight * .24f }) return 0f

        val gaps = boxes.zipWithNext { left, right ->
            (right.left - left.right).toFloat() / averageHeight
        }
        if (gaps.any { it !in -.12f.. .78f }) return 0f
        val meanGap = gaps.average().toFloat()
        val meanDeviation = gaps.map { abs(it - meanGap) }.average().toFloat()
        val spacingConsistency = (1f - meanDeviation / .38f).coerceIn(0f, 1f)
        val spacingPlausibility = (1f - abs(meanGap - .16f) / .72f).coerceIn(0f, 1f)
        return spacingConsistency * .55f + spacingPlausibility * .45f
    }

    private fun buildReading(
        mainBoxes: List<DigitBox>,
        smallBoxes: List<DigitBox>,
        template: TemplateSpec
    ): ChosenReading? {
        val selected = (mainBoxes + smallBoxes).sortedBy { it.left }
        if (selected.isEmpty()) return null
        if (mainBoxes.size !in template.minLargeDigits..template.maxLargeDigits) return null
        if (smallBoxes.size != template.smallFractionSlots) return null

        val digitString = selected.joinToString("") { it.digit.toString() }
        val integerValue = digitString.toLongOrNull() ?: return null
        var divisor = 1.0
        repeat(template.decimalPlaces) { divisor *= 10.0 }
        val value = integerValue.toDouble() / divisor

        // Number of displayed large characters minus the large fractional positions is the
        // number of visible integer characters. Lowercase fractional positions come afterwards.
        val boundaryIndex = (mainBoxes.size - template.largeFractionSlots).coerceIn(0, selected.size)
        val decimalX = when {
            !template.hasDecimal -> -1
            boundaryIndex == 0 -> (selected.first().left - max(2, selected.first().width / 5)).coerceAtLeast(0)
            boundaryIndex >= selected.size -> selected.last().right + max(2, selected.last().width / 5)
            else -> (selected[boundaryIndex - 1].right + selected[boundaryIndex].left) / 2
        }
        val baseline = mainBoxes.map { it.bottom }.average().toInt()
        val averageHeight = mainBoxes.map { it.height }.average().toInt().coerceAtLeast(1)
        val decimalY = (baseline - max(1, averageHeight / 12)).coerceAtLeast(0)
        return ChosenReading(
            digitString,
            value,
            selected,
            decimalX,
            decimalY,
            template.hasDecimal,
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
        // Explore the common seven-segment digit proportions and nearby widths. A coarse
        // vertical step misses horizontal bars when the candidate cell is shifted by only
        // a few pixels, which made digits 2/3/4 look like a narrow "1".
        val ratios = floatArrayOf(.30f, .34f, .38f, .42f, .46f, .50f, .54f)
        val minHeight = max(12, min(width, height) / 45)
        val maxHeight = min((height * .72f).toInt(), (width * .65f).toInt()).coerceAtLeast(minHeight)
        var digitHeight = minHeight

        while (digitHeight <= maxHeight) {
            // Search more densely than the first prototype. Template matching later rejects
            // false candidates, so candidate discovery should favor recall over precision.
            val stepX = max(2, digitHeight / 12)
            val stepY = max(1, digitHeight / 10)
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
                        if (confidence >= .24f) {
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
            digitHeight = max(digitHeight + 2, (digitHeight * 1.10f).roundToInt())
            if (candidates.size > 6000) {
                val pruned = suppressCandidates(candidates, 1200)
                candidates.clear()
                candidates.addAll(pruned)
            }
        }
        return suppressCandidates(candidates, 900)
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

        var top1 = 0f
        var top2 = 0f
        var top3 = 0f
        var mask1 = 0
        var mask2 = 0
        var mask3 = 0
        var polarity1 = 1
        var polarity2 = 1
        var polarity3 = 1
        var digit1 = '?'
        var digit2 = '?'
        var digit3 = '?'
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
                if (score > top1) {
                    top3 = top2; mask3 = mask2; polarity3 = polarity2; digit3 = digit2
                    top2 = top1; mask2 = mask1; polarity2 = polarity1; digit2 = digit1
                    top1 = score; mask1 = mask; polarity1 = polarity; digit1 = digitChars[digitIndex]
                } else if (score > top2) {
                    top3 = top2; mask3 = mask2; polarity3 = polarity2; digit3 = digit2
                    top2 = score; mask2 = mask; polarity2 = polarity; digit2 = digitChars[digitIndex]
                } else if (score > top3) {
                    top3 = score; mask3 = mask; polarity3 = polarity; digit3 = digitChars[digitIndex]
                }
            }
        }

        if (top1 < .28f) {
            digitOut[0] = digit1
            return top1
        }
        val fineSamples = FloatArray(segmentSampleZones.size)
        for (i in segmentSampleZones.indices) {
            val z = segmentSampleZones[i]
            fineSamples[i] = mean(
                integral, width, height,
                x + (z[0] * digitWidth).toInt(),
                y + (z[1] * digitHeight).toInt(),
                x + max((z[2] * digitWidth).toInt(), (z[0] * digitWidth).toInt() + 1),
                y + max((z[3] * digitHeight).toInt(), (z[1] * digitHeight).toInt() + 1)
            ) - background
        }

        var finalScore = 0f
        var finalDigit = digit1
        val candidateScores = floatArrayOf(top1, top2, top3)
        val candidateMasks = intArrayOf(mask1, mask2, mask3)
        val candidatePolarities = intArrayOf(polarity1, polarity2, polarity3)
        val candidateDigits = charArrayOf(digit1, digit2, digit3)
        for (candidate in candidateScores.indices) {
            if (candidateScores[candidate] <= 0f) continue
            val fit = continuityFit(
                fineSamples,
                candidateMasks[candidate],
                candidatePolarities[candidate]
            )
            val score = candidateScores[candidate] * fit
            if (score > finalScore) {
                finalScore = score
                finalDigit = candidateDigits[candidate]
            }
        }
        digitOut[0] = finalDigit
        return finalScore
    }

    private fun continuityFit(samples: FloatArray, mask: Int, polarity: Int): Float {
        var activeSum = 0f
        var activeCount = 0
        var inactiveEnergy = 0f
        var inactiveCount = 0
        for (segment in 0..6) {
            for (sample in 0..2) {
                val value = samples[segment * 3 + sample] * polarity
                if ((mask and (1 shl segment)) != 0) {
                    activeSum += value
                    activeCount++
                }
            }
        }
        val activeMean = activeSum / activeCount.coerceAtLeast(1)
        if (activeMean < 7f) return 0f

        var activeError = 0f
        var covered = 0
        for (segment in 0..6) {
            for (sample in 0..2) {
                val value = samples[segment * 3 + sample] * polarity
                if ((mask and (1 shl segment)) != 0) {
                    val delta = value - activeMean
                    activeError += delta * delta
                    if (value > max(5f, activeMean * .22f)) covered++
                } else {
                    val residual = max(0f, abs(value) - activeMean * .12f)
                    inactiveEnergy += residual * residual
                    inactiveCount++
                }
            }
        }
        val deviation = sqrt(activeError / activeCount.coerceAtLeast(1))
        val inactiveDeviation = if (inactiveCount > 0) {
            sqrt(inactiveEnergy / inactiveCount)
        } else 0f
        val consistency = (1f - deviation / max(1f, activeMean * .90f)).coerceIn(0f, 1f)
        val offSegmentFit = (1f - inactiveDeviation / max(1f, activeMean * .65f)).coerceIn(0f, 1f)
        val coverage = covered.toFloat() / activeCount.coerceAtLeast(1)
        return consistency * offSegmentFit * coverage
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
                // Suppress near-identical rectangles even if a shifted scan classified the
                // same physical glyph differently. A broad false rectangle has low IoU and
                // cannot erase a tighter real digit box.
                val intersectionWidth = max(
                    0,
                    min(candidate.right, previous.right) - max(candidate.left, previous.left)
                )
                val intersectionHeight = max(
                    0,
                    min(candidate.bottom, previous.bottom) - max(candidate.top, previous.top)
                )
                val intersection = intersectionWidth.toFloat() * intersectionHeight
                val candidateArea = candidate.width.toFloat() * candidate.height
                val previousArea = previous.width.toFloat() * previous.height
                val union = candidateArea + previousArea - intersection
                val overlap = if (union > 0f) intersection / union else 0f
                overlap >= .66f
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
