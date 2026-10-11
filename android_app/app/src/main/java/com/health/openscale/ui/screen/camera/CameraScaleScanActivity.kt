/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.health.openscale.ui.screen.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.utils.ConverterUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class CameraScaleScanActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsFacade
    @Inject lateinit var measurementFacade: MeasurementFacade
    @Inject lateinit var userFacade: UserFacade

    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var confirm: Button
    private lateinit var retry: Button
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var camera: CameraDevice? = null
    private var cameraOpening = false
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var stableDigits: String? = null
    private var repeatedFrames = 0
    private var stableAt = 0L
    private var savedFormat = "XXX.X"
    private var savedUnit = "KG"
    private var stableWeight: Float? = null
    private var saving = false
    @Volatile private var settingsLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraThread = HandlerThread("ScaleCameraFrames").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
        buildUi()
        lifecycleScope.launch {
            savedFormat = settings.observeSetting(FORMAT_KEY, "XXX.X").first()
            savedUnit = settings.observeSetting(UNIT_KEY, "KG").first()
            settingsLoaded = true
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            // The TextureView listener opens the camera when its surface is ready.
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 41)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        preview = TextureView(this)
        val previewContainer = FrameLayout(this)
        previewContainer.addView(preview, FrameLayout.LayoutParams(-1, -1))
        previewContainer.addView(object : View(this) {
            private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.GREEN
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                canvas.drawRect(width * .12f, height * .25f, width * .88f, height * .75f, guidePaint)
            }
        }, FrameLayout.LayoutParams(-1, -1))
        root.addView(previewContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
            text = getString(com.health.openscale.R.string.camera_align_display)
            setPadding(16, 12, 16, 12)
        }
        root.addView(status)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        retry = Button(this).apply {
            text = getString(com.health.openscale.R.string.camera_retry)
            setOnClickListener { stableDigits = null; stableWeight = null; repeatedFrames = 0; confirm.visibility = View.GONE; status.text = getString(com.health.openscale.R.string.camera_align_display) }
        }
        confirm = Button(this).apply {
            text = getString(com.health.openscale.R.string.camera_confirm)
            visibility = View.GONE
            setOnClickListener { saveRecognizedMeasurement() }
        }
        row.addView(retry); row.addView(confirm)
        root.addView(row)
        setContentView(root)
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
        else finish()
    }

    private fun openCamera() {
        if (isFinishing || camera != null || cameraOpening || !preview.isAvailable) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            val manager = getSystemService(CameraManager::class.java)
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.first()
            val sizes = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = sizes?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
                ?.filter { it.width >= 320 && it.height >= 240 }
                ?.minByOrNull { it.width * it.height } ?: return
            reader = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.YUV_420_888, 2).also {
                it.setOnImageAvailableListener({ source ->
                    val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try { processImage(image) } finally { image.close() }
                }, cameraHandler)
            }
            cameraOpening = true
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraOpening = false
                    camera = device
                    val texture = preview.surfaceTexture ?: return
                    texture.setDefaultBufferSize(size.width, size.height)
                    val surfaces = listOf(Surface(texture), reader!!.surface)
                    device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surfaces[0]); addTarget(surfaces[1])
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            }.build()
                            s.setRepeatingRequest(request, null, cameraHandler)
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { runOnUiThread { status.text = getString(com.health.openscale.R.string.camera_error) } }
                    }, cameraHandler)
                }
                override fun onDisconnected(device: CameraDevice) { cameraOpening = false; device.close(); camera = null }
                override fun onError(device: CameraDevice, error: Int) { cameraOpening = false; device.close(); camera = null; runOnUiThread { status.text = getString(com.health.openscale.R.string.camera_error) } }
            }, cameraHandler)
        } catch (_: Exception) {
            cameraOpening = false
            status.text = getString(com.health.openscale.R.string.camera_error)
        }
    }

    private fun processImage(image: Image) {
        if (!settingsLoaded || stableWeight != null || saving) return
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val width = image.width
        val height = image.height
        val gray = ByteArray(width * height)
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * rowStride + x * pixelStride
            if (i < bytes.size) gray[y * width + x] = bytes[i]
        }
        val digits = ScaleDisplayRecognizer.read(gray, width, height)
        if (digits == null) { stableDigits = null; repeatedFrames = 0; return }
        if (digits == stableDigits) repeatedFrames++ else { stableDigits = digits; repeatedFrames = 1 }
        if (repeatedFrames >= 4) {
            val decimals = when (savedFormat) { "XXX" -> 0; "XXX.X" -> 1; "XXX.XX" -> 2; "XXX.XXX" -> 3; else -> 1 }
            val raw = digits.toFloat() / Math.pow(10.0, decimals.toDouble()).toFloat()
            stableWeight = raw
            stableAt = System.currentTimeMillis()
            runOnUiThread {
                val symbols = DecimalFormatSymbols(Locale.getDefault())
                val fmt = DecimalFormat(if (decimals == 0) "0" else "0." + "0".repeat(decimals), symbols)
                status.text = getString(com.health.openscale.R.string.camera_recognized, fmt.format(raw), UnitType.valueOf(savedUnit).displayName)
                confirm.visibility = View.VISIBLE
            }
        }
    }

    private fun saveRecognizedMeasurement() {
        val value = stableWeight ?: return
        if (saving) return
        saving = true
        confirm.isEnabled = false
        lifecycleScope.launch {
            try {
                val user = userFacade.observeSelectedUser().first()
                if (user == null) {
                    status.text = getString(com.health.openscale.R.string.camera_no_user)
                    saving = false; confirm.isEnabled = true; return@launch
                }
                val types = measurementFacade.getAllMeasurementTypes().first()
                val weightType = types.firstOrNull { it.identity == MeasurementType.WEIGHT.identity }
                if (weightType == null) {
                    status.text = getString(com.health.openscale.R.string.camera_save_error)
                    saving = false; confirm.isEnabled = true; return@launch
                }
                val displayUnit = UnitType.valueOf(savedUnit)
                val storedValue = ConverterUtils.convertFloatValueUnit(value, displayUnit, weightType.unit)
                val result = measurementFacade.saveMeasurement(
                    Measurement(userId = user.id, timestamp = stableAt),
                    listOf(MeasurementValue(measurementId = 0, typeId = weightType.id, floatValue = storedValue))
                )
                if (result.isSuccess && result.getOrNull()?.let { it >= 0 } == true) finish()
                else {
                    status.text = getString(com.health.openscale.R.string.camera_save_error)
                    saving = false; confirm.isEnabled = true
                }
            } catch (_: Exception) {
                status.text = getString(com.health.openscale.R.string.camera_save_error)
                saving = false; confirm.isEnabled = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::preview.isInitialized) preview.post { openCamera() }
    }

    override fun onPause() {
        session?.close(); session = null
        camera?.close(); camera = null
        reader?.close(); reader = null
        super.onPause()
    }

    override fun onDestroy() {
        session?.close(); camera?.close(); reader?.close()
        cameraThread?.quitSafely()
        cameraThread = null; cameraHandler = null
        super.onDestroy()
    }

    companion object {
        const val FORMAT_KEY = "camera_scale_display_format"
        const val UNIT_KEY = "camera_scale_display_unit"
    }
}
