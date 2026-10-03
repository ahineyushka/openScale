/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.health.openscale.core.bluetooth.scales

import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * REDMOND SkyBalance floor scales using the standard Bluetooth SIG Weight Scale Service.
 *
 * Verified against a real RS-744S Bluetooth HCI capture:
 * - local name: RS-744S
 * - Weight Scale Service: 0x181D
 * - Weight Scale Feature: 0x2A9E (read)
 * - Weight Measurement: 0x2A9D (indicate)
 *
 * Ready for Sky groups RS-73S, RS-744S, RS-745S, RS-762S and RS-773S into the same
 * floor-scale family. The five product names therefore share this handler.
 *
 * The real RS-744S capture contains:
 * - live 0x2A9D frames with flags=0x00 and no timestamp;
 * - stored/result 0x2A9D frames with flags=0x02 and a timestamp.
 *
 * Only timestamped frames are persisted. The live frames are transient display updates and can
 * repeat many times during one weighing session.
 *
 * No authentication, custom 0x7802 service, Current Time write, Body Composition Service,
 * User Data Service or Battery Service was observed in the RS-744S capture.
 */
class RedmondSkybalanceHandler : ScaleDeviceHandler() {

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val model = MODEL_NAMES[device.name.trim().uppercase(Locale.US)] ?: return null

        val capabilities = setOf(DeviceCapability.LIVE_WEIGHT_STREAM)
        return DeviceSupport(
            displayName = "REDMOND " + model,
            capabilities = capabilities,
            implemented = capabilities,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        publishedTimestamps.clear()
        setNotifyOn(SVC_WEIGHT_SCALE, CHR_WEIGHT_MEASUREMENT)

        if (hasCharacteristic(SVC_WEIGHT_SCALE, CHR_WEIGHT_SCALE_FEATURE)) {
            readFrom(SVC_WEIGHT_SCALE, CHR_WEIGHT_SCALE_FEATURE)
        }
    }

    override fun onNotification(
        characteristic: UUID,
        data: ByteArray,
        user: ScaleUser
    ) {
        when (characteristic) {
            CHR_WEIGHT_MEASUREMENT -> {
                val parsed = parseWeightMeasurement(data) ?: run {
                    logW("REDMOND: invalid Weight Measurement payload ${data.toHexPreview(24)}")
                    return
                }

                if (!parsed.hasTimestamp) {
                    logD("REDMOND ${parsed.weightKg} kg live frame without timestamp; not storing")
                    return
                }

                val timestamp = parsed.measurement.dateTime?.time ?: return
                if (!publishedTimestamps.add(timestamp)) {
                    logD("Skipping repeated REDMOND measurement at ${parsed.measurement.dateTime}")
                    return
                }

                logD(
                    "REDMOND timestamped measurement: " +
                        "${parsed.weightKg} kg at ${parsed.measurement.dateTime}"
                )
                publish(parsed.measurement)
            }

            CHR_WEIGHT_SCALE_FEATURE -> {
                logD("REDMOND Weight Scale Feature: ${data.toHexPreview(16)}")
            }

            else -> {
                logD(
                    "REDMOND unhandled characteristic $characteristic " +
                        "${data.toHexPreview(24)}"
                )
            }
        }
    }

    override fun onDisconnected() {
        publishedTimestamps.clear()
    }

    internal data class ParsedWeight(
        val measurement: ScaleMeasurement,
        val weightKg: Float,
        val hasTimestamp: Boolean
    )

    /**
     * Decode Bluetooth SIG Weight Measurement (0x2A9D).
     *
     * Flags:
     * bit 0 = 0 kg / 1 lb
     * bit 1 = timestamp present
     * bit 2 = user ID present
     * bit 3 = BMI + height present
     *
     * RS-744S capture observed flags 0x00 and 0x02 only.
     */
    internal fun parseWeightMeasurement(data: ByteArray): ParsedWeight? {
        if (data.size < 3) return null

        val flags = u8(data, 0)
        var offset = 1

        val isLb = (flags and 0x01) != 0
        val hasTimestamp = (flags and 0x02) != 0
        val hasUserId = (flags and 0x04) != 0
        val hasBmiHeight = (flags and 0x08) != 0

        val rawWeight = u16le(data, offset)
        offset += 2

        val weightKg = if (isLb) {
            rawWeight * 0.01f * LB_TO_KG
        } else {
            rawWeight * 0.005f
        }

        if (weightKg <= 0f || !weightKg.isFinite()) return null

        val measurement = ScaleMeasurement()
        measurement[MeasurementType.WEIGHT] = Kg(weightKg)

        if (hasTimestamp) {
            if (offset + 7 > data.size) return null

            val year = u16le(data, offset)
            val month = u8(data, offset + 2)
            val day = u8(data, offset + 3)
            val hour = u8(data, offset + 4)
            val minute = u8(data, offset + 5)
            val second = u8(data, offset + 6)

            val calendar = Calendar.getInstance().apply {
                clear()
                isLenient = false
                set(year, month - 1, day, hour, minute, second)
            }

            measurement.dateTime = runCatching { calendar.time }.getOrNull() ?: return null
        }

        if (hasUserId || hasBmiHeight) {
            logW(
                "REDMOND optional Weight Measurement fields are not decoded yet: " +
                    "flags=0x${flags.toString(16)}"
            )
        }

        return ParsedWeight(
            measurement = measurement,
            weightKg = weightKg,
            hasTimestamp = hasTimestamp
        )
    }

    private fun u8(data: ByteArray, offset: Int): Int =
        data[offset].toInt() and 0xFF

    private fun u16le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)

    private val publishedTimestamps = mutableSetOf<Long>()

    private companion object {
        private val SVC_WEIGHT_SCALE = uuid16Static(0x181D)
        private val CHR_WEIGHT_MEASUREMENT = uuid16Static(0x2A9D)
        private val CHR_WEIGHT_SCALE_FEATURE = uuid16Static(0x2A9E)

        private val MODEL_NAMES = mapOf(
            "RS-73S" to "RS-73S",
            "RS-744S" to "RS-744S",
            "RS-745S" to "RS-745S",
            "RS-762S" to "RS-762S",
            "RS-773S" to "RS-773S"
        )

        private const val LB_TO_KG = 0.45359237f

        private fun uuid16Static(short: Int): UUID =
            UUID.fromString(
                String.format(
                    Locale.US,
                    "0000%04x-0000-1000-8000-00805f9b34fb",
                    short
                )
            )
    }
}
