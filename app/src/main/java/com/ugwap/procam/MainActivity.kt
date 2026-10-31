package com.ugwap.procam

import android.Manifest
import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.AspectRatio
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.slider.Slider
import com.ugwap.procam.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class Mode { PHOTO, VIDEO }
    private enum class FrameRatio { SQ, R43, R169 }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraProvider: ProcessCameraProvider

    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var ultraWideSelector: CameraSelector? = null
    private var ultraWideRatio = 0.6f
    private var usingUltraWide = false
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var mode = Mode.PHOTO
    private var proEnabled = false
    private var stabEnabled = false
    private var flashOn = false
    private var gridOn = true
    private var watermarkOn = false

    // Nilai manual (pro mode)
    private var iso = 100
    private var shutterNs = 16_666_666L
    private var focusDiopter = 0f

    // Batas kemampuan kamera
    private var isoRange = android.util.Range(100, 3200)
    private var shutterRange = android.util.Range(1_000L, 500_000_000L)
    private var minFocusDistance = 0f
    private var maxZoom = 1f
    private var stabSupported = false
    private var stabMode = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON

    // FPS: null = biarkan kamera memilih (auto)
    private var fpsRange: android.util.Range<Int>? = null
    private val fpsOptions = mutableListOf<android.util.Range<Int>>()
    private var fpsInitialized = false
    private var forcedFps = false
    private var frameRatio = FrameRatio.R43
    private val cameraAspectRatio: Int
        get() = if (frameRatio == FrameRatio.R169) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3

    private var isUpdatingUi = false

    private var isBusy = false
    private var lastPhotoUri: Uri? = null

    // Kontrol manual live (tanpa rebind) untuk slider PRO
    private var camera2Control: Camera2CameraControl? = null
    private var liveProPending = false
    private val liveProRunnable = Runnable {
        liveProPending = false
        pushLiveProOptions()
    }

    private val uiExecutor by lazy { ContextCompat.getMainExecutor(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var recordStartedAt = 0L
    private var zoomAnimator: ValueAnimator? = null
    private val sembunyikanZoomRunnable = Runnable { sembunyikanZoomPanel() }

    private val tag = "ProCam"

    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = System.currentTimeMillis() - recordStartedAt
            val detik = elapsed / 1000
            binding.timerText.text = String.format(Locale.US, "%02d:%02d", detik / 60, detik % 60)
            handler.postDelayed(this, 500)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hasil ->
            if (hasil[Manifest.permission.CAMERA] == true) {
                startCamera()
            } else {
                Toast.makeText(this, "Izin kamera dibutuhkan untuk memakai aplikasi ini.", Toast.LENGTH_LONG).show()
                finish()
            }
            updateWatermarkChip()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        setupUi()
        setupGestures()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    // ================= UI =================

    private fun applyWindowInsets() {
        val density = resources.displayMetrics.density
        val padStart = (12 * density).toInt()
        val padTop = (10 * density).toInt()
        val padEnd = (12 * density).toInt()
        val padBottom = (14 * density).toInt()
        val padBottomBar = (10 * density).toInt()
        val padSideBar = (14 * density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(padStart + bars.left, padTop + bars.top, padEnd + bars.right, padBottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomContainer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(padSideBar, 0, padSideBar, bars.bottom + padBottomBar)
            insets
        }
    }

    private fun setupUi() {
        binding.gridOverlay.gridEnabled = gridOn
        setChipActive(binding.btnGrid, gridOn)
        setModeChipActive(binding.btnFoto, true)
        setModeChipActive(binding.btnVideo, false)
        setChipActive(binding.btnPro, false)
        updateFlashChip()
        updateWatermarkChip()
        updateFpsChip()
        updateRatioChip()
        binding.btnStab.isVisible = false
        updateStabChip()

        binding.previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateRatioMask()
        }

        binding.btnGrid.setOnClickListener {
            gridOn = !gridOn
            binding.gridOverlay.gridEnabled = gridOn
            setChipActive(binding.btnGrid, gridOn)
        }

        binding.btnFlash.setOnClickListener {
            flashOn = !flashOn
            updateFlashChip()
            if (mode == Mode.PHOTO) bindUseCases()
        }

        binding.btnWatermark.setOnClickListener {
            watermarkOn = !watermarkOn
            updateWatermarkChip()
        }

        binding.btnFps.setOnClickListener { showFpsDialog() }
        binding.btnRatio.setOnClickListener { showRatioDialog() }

        binding.btnFoto.setOnClickListener { switchMode(Mode.PHOTO) }
        binding.btnVideo.setOnClickListener { switchMode(Mode.VIDEO) }

        binding.btnControls.setOnClickListener {
            binding.controlsPanel.isVisible = !binding.controlsPanel.isVisible
        }

        binding.btnPro.setOnClickListener {
            proEnabled = !proEnabled
            binding.proPanel.isVisible = proEnabled
            binding.controlsPanel.isVisible = false
            setChipActive(binding.btnPro, proEnabled)
            bindUseCases()
        }

        binding.btnResetPro.setOnClickListener {
            iso = isoRange.lower
            shutterNs = clampShutter(1_000_000_000L / 60)
            focusDiopter = 0f
            syncSlidersFromState()
            bindUseCases()
        }

        binding.btnFlip.setOnClickListener {
            if (recording != null) return@setOnClickListener
            usingUltraWide = false
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            fpsInitialized = false
            if (::cameraProvider.isInitialized) {
                detectUltraWide()
                readHardwareInfo()
            }
            bindUseCases()
        }

        binding.btnZoomUw.setOnClickListener { pilihUltraWide() }
        binding.btnZoom1.setOnClickListener { setZoom(1f) }
        binding.btnZoom2.setOnClickListener { setZoom(2f) }
        binding.btnZoom5.setOnClickListener { setZoom(5f) }

        val tahanZoom = View.OnLongClickListener {
            tampilkanZoomPanel()
            true
        }
        binding.zoomRow.setOnLongClickListener(tahanZoom)
        binding.btnZoomUw.setOnLongClickListener(tahanZoom)
        binding.btnZoom1.setOnLongClickListener(tahanZoom)
        binding.btnZoom2.setOnLongClickListener(tahanZoom)
        binding.btnZoom5.setOnLongClickListener(tahanZoom)

        binding.zoomSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingUi) return@addOnChangeListener
            zoomAnimator?.cancel()
            camera?.cameraControl?.setLinearZoom(value)
            perbaruiZoomValue(camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f)
            jadwalkanSembunyikanZoom()
        }
        binding.zoomSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                handler.removeCallbacks(sembunyikanZoomRunnable)
            }

            override fun onStopTrackingTouch(slider: Slider) {
                jadwalkanSembunyikanZoom()
            }
        })

        binding.btnStab.setOnClickListener {
            if (!stabSupported) return@setOnClickListener
            stabEnabled = !stabEnabled
            updateStabChip()
            pushLiveProOptions()
        }

        binding.shutterRing.setOnClickListener { onShutter() }
        binding.shutterInner.setOnClickListener { onShutter() }
        binding.thumbButton.setOnClickListener { bukaFotoTerakhir() }

        setupProSliders()
    }

    private fun setupProSliders() {
        binding.isoSlider.addOnChangeListener { _, value, _ ->
            if (isUpdatingUi) return@addOnChangeListener
            iso = sliderToIso(value)
            binding.isoValue.text = "ISO $iso"
            updateHud()
            scheduleLiveProOptions()
        }
        binding.isoSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) = applyLiveProNow()
        })

        binding.shutterSlider.addOnChangeListener { _, value, _ ->
            if (isUpdatingUi) return@addOnChangeListener
            shutterNs = sliderToShutter(value)
            binding.shutterValue.text = formatShutter(shutterNs)
            updateHud()
            scheduleLiveProOptions()
        }
        binding.shutterSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) = applyLiveProNow()
        })

        binding.focusSlider.addOnChangeListener { _, value, _ ->
            if (isUpdatingUi) return@addOnChangeListener
            focusDiopter = value
            binding.focusValue.text = formatFocus(focusDiopter)
            updateHud()
            scheduleLiveProOptions()
        }
        binding.focusSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) = applyLiveProNow()
        })
    }

    /** Terapkan nilai manual langsung ke sesi kamera tanpa rebind (fokus/ISO/shutter live). */
    private fun scheduleLiveProOptions() {
        if (liveProPending) return
        liveProPending = true
        handler.postDelayed(liveProRunnable, 40L)
    }

    private fun applyLiveProNow() {
        handler.removeCallbacks(liveProRunnable)
        liveProPending = false
        pushLiveProOptions()
    }

    private fun pushLiveProOptions() {
        val control = camera2Control ?: return
        try {
            val builder = CaptureRequestOptions.Builder()
            var adaOpsi = false
            if (proEnabled) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopter)
                adaOpsi = true
            }
            if (mode == Mode.VIDEO && stabSupported) {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    if (stabEnabled) stabMode
                    else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
                adaOpsi = true
            }
            if (!adaOpsi) return
            control.setCaptureRequestOptions(builder.build())
        } catch (e: Exception) {
            Log.w(tag, "Gagal menerapkan opsi manual live", e)
        }
    }

    private fun setChipActive(view: TextView, active: Boolean) {
        view.setBackgroundResource(if (active) R.drawable.bg_chip_on else R.drawable.bg_chip)
        view.setTextColor(if (active) 0xFF111111.toInt() else Color.WHITE)
    }

    /** Tab mode (VIDEO/FOTO): teks amber saat aktif, tanpa background. */
    private fun setModeChipActive(view: TextView, active: Boolean) {
        view.setTextColor(if (active) 0xFFFFC107.toInt() else 0xFFFFFFFF.toInt())
        view.alpha = if (active) 1f else 0.85f
    }

    /** Chip zoom: pil amber penuh saat aktif, teks amber saat tidak aktif. */
    private fun setZoomChipActive(view: TextView, active: Boolean) {
        if (active) {
            view.setBackgroundResource(R.drawable.bg_pill_amber)
            view.setTextColor(0xFF111111.toInt())
        } else {
            view.setBackgroundResource(android.R.color.transparent)
            view.setTextColor(0xFFFFC107.toInt())
        }
    }

    private fun updateFlashChip() {
        binding.btnFlash.text = if (flashOn) "Flash ON" else "Flash"
        setChipActive(binding.btnFlash, flashOn)
    }

    private fun updateWatermarkChip() {
        binding.btnWatermark.text = if (watermarkOn) "WM ON" else "WM"
        setChipActive(binding.btnWatermark, watermarkOn)
    }

    private fun updateStabChip() {
        binding.btnStab.text = if (stabEnabled) "STAB ON" else "STAB OFF"
        setChipActive(binding.btnStab, stabEnabled)
    }

    private fun updateFpsChip() {
        binding.btnFps.text = fpsRange?.let { "FPS ${it.upper}" } ?: "FPS auto"
        setChipActive(binding.btnFps, fpsRange != null)
    }

    private fun switchMode(newMode: Mode) {
        if (mode == newMode) return
        if (recording != null) stopRecording()
        mode = newMode
        setModeChipActive(binding.btnFoto, mode == Mode.PHOTO)
        setModeChipActive(binding.btnVideo, mode == Mode.VIDEO)
        binding.controlsPanel.isVisible = false
        binding.zoomRow.isVisible = true
        handler.removeCallbacks(sembunyikanZoomRunnable)
        sembunyikanZoomPanel()
        binding.btnStab.isVisible = mode == Mode.VIDEO && stabSupported
        updateStabChip()
        bindUseCases()
    }

    private fun onShutter() {
        if (mode == Mode.PHOTO) {
            takePhoto()
        } else {
            if (recording == null) startRecording() else stopRecording()
        }
    }

    // ================= Kamera =================

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                detectUltraWide()
                readHardwareInfo()
                bindUseCases()
            } catch (e: Exception) {
                Log.e(tag, "Gagal membuka kamera", e)
                Toast.makeText(this, "Kamera tidak dapat dibuka: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, uiExecutor)
    }

    private fun detectUltraWide() {
        ultraWideSelector = null
        try {
            val backs = cameraProvider.availableCameraInfos.filter {
                it.lensFacing == CameraSelector.LENS_FACING_BACK
            }
            val main = CameraSelector.DEFAULT_BACK_CAMERA.filter(backs).firstOrNull() ?: return

            fun focal35(info: androidx.camera.core.CameraInfo): Float {
                val c2 = Camera2CameraInfo.from(info)
                val f = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.minOrNull() ?: return 0f
                val s = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    ?: return 0f
                if (s.width <= 0f || s.height <= 0f) return 0f
                return f * 43.266f / kotlin.math.hypot(s.width, s.height)
            }

            val mainF = focal35(main)
            if (mainF <= 0f) return
            val uw = backs.filter { it != main }
                .map { it to focal35(it) }
                .filter { it.second > 0f }
                .minByOrNull { it.second } ?: return
            if (uw.second >= mainF * 0.92f) return

            ultraWideRatio = uw.second / mainF
            val id = Camera2CameraInfo.from(uw.first).cameraId
            ultraWideSelector = CameraSelector.Builder()
                .addCameraFilter { infos ->
                    infos.filter { Camera2CameraInfo.from(it).cameraId == id }
                }
                .build()
        } catch (e: Exception) {
            Log.w(tag, "Deteksi ultrawide gagal", e)
            ultraWideSelector = null
        } finally {
            binding.btnZoomUw.text = String.format(Locale.US, "%.1fx", ultraWideRatio)
            binding.btnZoomUw.isVisible = ultraWideSelector != null &&
                cameraSelector != CameraSelector.DEFAULT_FRONT_CAMERA
        }
    }

    private fun readHardwareInfo() {
        val info = cameraSelector.filter(cameraProvider.availableCameraInfos).firstOrNull() ?: return
        val c2 = Camera2CameraInfo.from(info)

        isoRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: android.util.Range(100, 3200)
        shutterRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: android.util.Range(1_000L, 500_000_000L)
        minFocusDistance = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        val stabModes = c2.getCameraCharacteristic(
            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
        )
        val modePreview = if (Build.VERSION.SDK_INT >= 33) {
            CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
        } else -1
        stabSupported = stabModes?.contains(modePreview) == true ||
            stabModes?.contains(CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
        stabMode = if (stabModes?.contains(modePreview) == true) modePreview
        else CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON
        binding.btnStab.isVisible = mode == Mode.VIDEO && stabSupported

        val ranges = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: emptyArray()
        fpsOptions.clear()
        fpsOptions.addAll(ranges.distinct().sortedByDescending { it.upper })

        if (!fpsInitialized && fpsOptions.isNotEmpty()) {
            // Default: pakai rentang dengan FPS tertinggi yang didukung kamera
            fpsRange = fpsOptions.first()
            fpsInitialized = true
        }

        iso = iso.coerceIn(isoRange.lower, isoRange.upper)
        shutterNs = clampShutter(shutterNs)
        syncSlidersFromState()
        updateFpsChip()
        updateHud()
    }

    private fun bindUseCases() {
        if (!::cameraProvider.isInitialized) return
        if (recording != null) return

        try {
            cameraProvider.unbindAll()
        } catch (e: Exception) {
            Log.w(tag, "unbindAll gagal", e)
        }

        val rotation = binding.previewView.display?.rotation ?: Surface.ROTATION_0

        val previewBuilder = Preview.Builder()
            .setTargetRotation(rotation)
            .setTargetAspectRatio(cameraAspectRatio)
        fpsRange?.let { previewBuilder.setTargetFrameRate(it) }
        if (fpsRange != null) applyFpsOption(Camera2Interop.Extender(previewBuilder))
        if (proEnabled) applyManualOptions(Camera2Interop.Extender(previewBuilder))
        if (mode == Mode.VIDEO && stabSupported && stabEnabled) {
            applyStabOption(Camera2Interop.Extender(previewBuilder))
        }

        val useCases = mutableListOf<UseCase>()
        val newPreview = previewBuilder.build()
        newPreview.setSurfaceProvider(binding.previewView.surfaceProvider)
        preview = newPreview
        useCases += newPreview

        if (mode == Mode.PHOTO) {
            val builder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                .setTargetRotation(rotation)
                .setTargetAspectRatio(cameraAspectRatio)

            if (fpsRange != null) applyFpsOption(Camera2Interop.Extender(builder))
            if (proEnabled) applyManualOptions(Camera2Interop.Extender(builder))

            val newCapture = builder.build()
            imageCapture = newCapture
            useCases += newCapture
        } else {
            val qualities = if ((fpsRange?.upper ?: 30) > 30) {
                listOf(Quality.FHD, Quality.HD)
            } else {
                listOf(Quality.UHD, Quality.FHD, Quality.HD)
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        qualities,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
                    )
                )
                .build()

            val builder = VideoCapture.Builder(recorder).setTargetRotation(rotation)
            fpsRange?.let { builder.setTargetFrameRate(it) }
            if (fpsRange != null) applyFpsOption(Camera2Interop.Extender(builder))
            if (stabSupported && stabEnabled) {
                applyStabOption(Camera2Interop.Extender(builder))
            }

            val newVideo = builder.build()
            videoCapture = newVideo
            useCases += newVideo
        }

        try {
            val bound = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                newPreview,
                *useCases.drop(1).toTypedArray()
            )
            camera = bound

            camera2Control = try {
                Camera2CameraControl.from(bound.cameraControl)
            } catch (e: Exception) {
                Log.w(tag, "Camera2CameraControl tidak tersedia", e)
                null
            }
            if (proEnabled || (mode == Mode.VIDEO && stabSupported)) pushLiveProOptions()

            maxZoom = bound.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
            binding.btnZoom5.isVisible = maxZoom >= 5f
            binding.btnZoom2.isVisible = maxZoom >= 2f
            updateZoomChips(1f)

            bound.cameraInfo.zoomState.observe(this) { state ->
                if (state != null) {
                    updateZoomChips(state.zoomRatio)
                    if (binding.zoomPanel.isVisible) {
                        isUpdatingUi = true
                        if (kotlin.math.abs(binding.zoomSlider.value - state.linearZoom) > 0.001f) {
                            binding.zoomSlider.value = state.linearZoom
                        }
                        isUpdatingUi = false
                        perbaruiZoomValue(state.zoomRatio)
                    }
                }
            }

            updateHud()
            updateRatioMask()
        } catch (e: Exception) {
            Log.e(tag, "Gagal bind use case", e)
            if (forcedFps) {
                forcedFps = false
                fpsRange = fpsOptions.firstOrNull()
                updateFpsChip()
                Toast.makeText(
                    this,
                    "FPS yang dipaksa tidak didukung kamera; kembali ke ${fpsRange?.upper ?: "auto"} fps.",
                    Toast.LENGTH_LONG
                ).show()
                bindUseCases()
            } else {
                Toast.makeText(this, "Konfigurasi kamera gagal: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applyManualOptions(extender: Camera2Interop.Extender<*>) {
        extender
            .setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopter)
    }

    private fun applyStabOption(extender: Camera2Interop.Extender<*>) {
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            stabMode
        )
    }

    private fun applyFpsOption(extender: Camera2Interop.Extender<*>) {
        fpsRange?.let {
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
        }
    }

    private fun pilihUltraWide() {
        if (recording != null) return
        val uw = ultraWideSelector ?: return
        if (usingUltraWide) return
        usingUltraWide = true
        cameraSelector = uw
        if (::cameraProvider.isInitialized) readHardwareInfo()
        bindUseCases()
    }

    private fun setZoom(ratio: Float) {
        zoomAnimator?.cancel()
        handler.removeCallbacks(sembunyikanZoomRunnable)
        sembunyikanZoomPanel()
        if (usingUltraWide) {
            usingUltraWide = false
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            if (::cameraProvider.isInitialized) readHardwareInfo()
            bindUseCases()
            handler.postDelayed({ animateZoomToRatio(ratio) }, 250L)
            return
        }
        animateZoomToRatio(ratio)
    }

    private fun ratioToLinear(ratio: Float, min: Float, max: Float): Float {
        if (max <= min || min <= 0f) return 0f
        return (ln(ratio / min) / ln(max / min)).coerceIn(0f, 1f)
    }

    private fun animateZoomToRatio(ratio: Float) {
        val cam = camera ?: return
        val state = cam.cameraInfo.zoomState.value ?: return
        val min = state.minZoomRatio
        val max = state.maxZoomRatio
        val target = ratio.coerceIn(min, max)
        val dari = state.linearZoom
        val ke = ratioToLinear(target, min, max)
        if (kotlin.math.abs(ke - dari) < 0.0005f) return

        zoomAnimator?.cancel()
        zoomAnimator = ValueAnimator.ofFloat(dari, ke).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener { cam.cameraControl.setLinearZoom(it.animatedValue as Float) }
            start()
        }
    }

    private fun tampilkanZoomPanel() {
        val state = camera?.cameraInfo?.zoomState?.value ?: return
        isUpdatingUi = true
        binding.zoomSlider.value = state.linearZoom
        isUpdatingUi = false
        perbaruiZoomValue(state.zoomRatio)
        val baru = !binding.zoomPanel.isVisible
        binding.zoomPanel.animate().cancel()
        binding.zoomPanel.isVisible = true
        binding.zoomPanel.alpha = 1f
        if (baru) {
            binding.zoomPanel.alpha = 0f
            binding.zoomPanel.animate().alpha(1f).setDuration(140L).start()
        }
        jadwalkanSembunyikanZoom()
    }

    private fun sembunyikanZoomPanel() {
        if (!binding.zoomPanel.isVisible) return
        binding.zoomPanel.animate().cancel()
        binding.zoomPanel.animate()
            .alpha(0f)
            .setDuration(140L)
            .withEndAction {
                if (binding.zoomPanel.alpha <= 0.01f) binding.zoomPanel.isVisible = false
            }
            .start()
    }

    private fun perbaruiZoomValue(ratio: Float) {
        binding.zoomValue.text = String.format(Locale.US, "%.1fx", ratio)
    }

    private fun jadwalkanSembunyikanZoom() {
        handler.removeCallbacks(sembunyikanZoomRunnable)
        handler.postDelayed(sembunyikanZoomRunnable, 3000L)
    }

    private fun updateZoomChips(current: Float) {
        if (usingUltraWide) {
            setZoomChipActive(binding.btnZoomUw, true)
            setZoomChipActive(binding.btnZoom1, false)
            setZoomChipActive(binding.btnZoom2, false)
            setZoomChipActive(binding.btnZoom5, false)
            return
        }
        setZoomChipActive(binding.btnZoomUw, false)
        setZoomChipActive(binding.btnZoom1, current < 1.8f)
        setZoomChipActive(binding.btnZoom2, current >= 1.8f && current < 3.5f)
        setZoomChipActive(binding.btnZoom5, current >= 3.5f)
    }

    private fun updateHud() {
        val resolusi = imageCapture?.resolutionInfo?.resolution?.let { "${it.width}x${it.height}" }
        val fpsText = fpsRange?.let {
            if (forcedFps) "~${it.upper}fps (paksa)" else "~${it.upper}fps"
        } ?: "auto"
        val proText = if (proEnabled) "ISO $iso · ${formatShutter(shutterNs)}" else "AUTO"
        binding.hudText.text = listOfNotNull(resolusi, fpsText, proText).joinToString(" · ")
    }

    private fun showFpsDialog() {
        val pilihan = mutableListOf<android.util.Range<Int>?>(null)
        val label = mutableListOf("Auto (bawaan kamera)")
        fpsOptions.forEach { r ->
            pilihan.add(r)
            label.add("${r.lower}–${r.upper} fps")
        }
        listOf(60, 120).forEach { target ->
            if (fpsOptions.none { it.upper >= target }) {
                pilihan.add(android.util.Range(target, target))
                label.add("$target fps (paksa)")
            }
        }

        val checked = pilihan.indexOf(fpsRange).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Target FPS")
            .setSingleChoiceItems(label.toTypedArray(), checked) { dialog, which ->
                val baru = pilihan[which]
                forcedFps = baru != null && fpsOptions.none { it == baru }
                fpsRange = baru
                updateFpsChip()
                bindUseCases()
                if (forcedFps) {
                    Toast.makeText(
                        this,
                        "Mencoba ${baru?.upper} fps. Jika hardware menolak, aplikasi kembali ke FPS yang didukung.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun updateRatioChip() {
        binding.btnRatio.text = when (frameRatio) {
            FrameRatio.SQ -> "1:1"
            FrameRatio.R169 -> "16:9"
            else -> "4:3"
        }
        setChipActive(binding.btnRatio, frameRatio != FrameRatio.R43)
        updateRatioMask()
    }

    private fun updateRatioMask() {
        val square = frameRatio == FrameRatio.SQ
        binding.ratioMaskTop.isVisible = square
        binding.ratioMaskBottom.isVisible = square
        if (!square) return

        val h = binding.previewView.height
        if (h <= 0) return

        val res = imageCapture?.resolutionInfo?.resolution
        val frac = if (res != null && res.width > 0) {
            res.height.toFloat() / res.width
        } else {
            0.75f
        }
        val band = (h * frac).toInt().coerceIn(0, h)
        val strip = (h - band) / 2

        listOf(binding.ratioMaskTop, binding.ratioMaskBottom).forEach { v ->
            val lp = v.layoutParams as android.widget.FrameLayout.LayoutParams
            if (lp.height != strip) {
                lp.height = strip
                v.layoutParams = lp
            }
        }
    }

    private fun showRatioDialog() {
        val label = arrayOf("1:1", "4:3", "16:9")
        val nilai = arrayOf(FrameRatio.SQ, FrameRatio.R43, FrameRatio.R169)
        val checked = nilai.indexOf(frameRatio).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Rasio foto")
            .setSingleChoiceItems(label, checked) { dialog, which ->
                frameRatio = nilai[which]
                updateRatioChip()
                bindUseCases()
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ================= Foto =================

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (isBusy) return
        isBusy = true

        val nama = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nama)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/ProCam")
            }
        }

        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        capture.takePicture(options, uiExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                isBusy = false
                val uri = output.savedUri
                val cropSquare = frameRatio == FrameRatio.SQ
                val pakaiWatermark = watermarkOn
                if (uri != null) {
                    lastPhotoUri = uri
                    if (!pakaiWatermark && !cropSquare) perbaruiThumbnail(uri)
                }
                if (uri != null && (pakaiWatermark || cropSquare)) {
                    Thread { prosesFoto(uri, cropSquare, pakaiWatermark) }.start()
                } else {
                    Toast.makeText(this@MainActivity, "Foto tersimpan di DCIM/ProCam", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                isBusy = false
                Log.e(tag, "Gagal mengambil foto", exception)
                Toast.makeText(this@MainActivity, "Gagal mengambil foto: ${exception.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun prosesFoto(uri: Uri, cropSquare: Boolean, watermark: Boolean) {
        try {
            val ukuran = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, ukuran) }

            var sample = 1
            var sisiTerbesar = maxOf(ukuran.outWidth, ukuran.outHeight)
            while (sisiTerbesar > 4096) {
                sample *= 2
                sisiTerbesar /= 2
            }

            val decode = BitmapFactory.Options().apply { inSampleSize = sample }
            val asli = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decode)
            } ?: return

            val exif = contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
            val rotasi = when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            var bmp = if (rotasi != 0f) {
                val m = Matrix().apply { postRotate(rotasi) }
                Bitmap.createBitmap(asli, 0, 0, asli.width, asli.height, m, true)
            } else {
                asli
            }

            if (cropSquare) {
                val sisi = minOf(bmp.width, bmp.height)
                val x = (bmp.width - sisi) / 2
                val y = (bmp.height - sisi) / 2
                val persegi = Bitmap.createBitmap(bmp, x, y, sisi, sisi)
                if (persegi !== bmp) {
                    if (bmp !== asli) bmp.recycle()
                    bmp = persegi
                }
            }

            if (watermark) {
                if (!bmp.isMutable) {
                    val mutabel = bmp.copy(Bitmap.Config.ARGB_8888, true)
                    if (mutabel != null) {
                        if (bmp !== asli) bmp.recycle()
                        bmp = mutabel
                    }
                }
                val kanvas = Canvas(bmp)
                val ukuranTeks = bmp.width / 32f
                val cat = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = ukuranTeks
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setShadowLayer(ukuranTeks / 5f, 0f, 0f, 0xCC000000.toInt())
                }

                val waktu = SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id", "ID")).format(Date())
                kanvas.drawText("ProCam · $waktu", bmp.width * 0.04f, bmp.height * 0.96f, cat)
            }

            contentResolver.openOutputStream(uri, "w")?.use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
            }

            if (bmp !== asli) bmp.recycle()
            asli.recycle()

            val pesan = buildString {
                append("Foto")
                if (cropSquare) append(" 1:1")
                if (watermark) append(" + watermark")
                append(" tersimpan di DCIM/ProCam")
            }
            runOnUiThread {
                perbaruiThumbnail(uri)
                Toast.makeText(this, pesan, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(tag, "Pemrosesan foto gagal", e)
            runOnUiThread {
                Toast.makeText(this, "Foto tersimpan, namun pemrosesan gagal.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun perbaruiThumbnail(uri: Uri) {
        Thread {
            val bmp = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.loadThumbnail(uri, android.util.Size(240, 240), null)
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 480) sample *= 2
                    val opt = BitmapFactory.Options().apply { inSampleSize = sample }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
                }
            } catch (e: Exception) {
                Log.w(tag, "Thumbnail gagal dimuat", e)
                null
            } ?: return@Thread

            runOnUiThread {
                binding.thumbButton.setImageBitmap(bmp)
                binding.thumbButton.isVisible = true
            }
        }.start()
    }

    private fun bukaFotoTerakhir() {
        val uri = lastPhotoUri ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (e: Exception) {
            Log.w(tag, "Tidak dapat membuka foto", e)
            Toast.makeText(this, "Tidak ada aplikasi galeri untuk membuka foto.", Toast.LENGTH_SHORT).show()
        }
    }

    // ================= Video =================

    private fun startRecording() {
        val video = videoCapture ?: return

        val nama = "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nama)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/ProCam")
            }
        }

        val options = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = video.output.prepareRecording(this, options)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }

        recording = pending.start(uiExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordStartedAt = System.currentTimeMillis()
                    handler.post(timerRunnable)
                    binding.timerText.isVisible = true
                    binding.shutterInner.setBackgroundResource(R.drawable.bg_stop)
                    binding.btnFlip.isEnabled = false
                }

                is VideoRecordEvent.Finalize -> {
                    handler.removeCallbacks(timerRunnable)
                    binding.timerText.isVisible = false
                    binding.shutterInner.setBackgroundResource(R.drawable.bg_shutter_inner)
                    binding.btnFlip.isEnabled = true
                    recording = null

                    if (event.hasError()) {
                        Toast.makeText(this, "Rekaman gagal (kode ${event.error}).", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Video tersimpan di DCIM/ProCam", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
    }

    // ================= Gesture =================

    private fun setupGestures() {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                zoomAnimator?.cancel()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val state = cam.cameraInfo.zoomState.value ?: return true
                val faktor = detector.scaleFactor
                if (faktor <= 0f || !faktor.isFinite()) return true
                val rentang = ln(state.maxZoomRatio / state.minZoomRatio)
                if (rentang <= 0f) return true

                val delta = ln(faktor) / rentang
                val baru = (state.linearZoom + delta).coerceIn(0f, 1f)
                cam.cameraControl.setLinearZoom(baru)

                if (binding.zoomPanel.isVisible) {
                    isUpdatingUi = true
                    binding.zoomSlider.value = baru
                    isUpdatingUi = false
                    perbaruiZoomValue(state.minZoomRatio * (state.maxZoomRatio / state.minZoomRatio).pow(baru))
                    jadwalkanSembunyikanZoom()
                }
                return true
            }
        })

        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                fokusKe(e.x, e.y)
                return true
            }
        })

        binding.previewView.setOnTouchListener { view, event ->
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) tapDetector.onTouchEvent(event)
            view.performClick()
            true
        }
    }

    private fun fokusKe(x: Float, y: Float) {
        if (proEnabled) return
        val point = binding.previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        camera?.cameraControl?.startFocusAndMetering(action)

        tampilkanFokusRing(x, y)
    }

    private fun tampilkanFokusRing(x: Float, y: Float) {
        val ring = binding.focusRing
        val setengah = ring.width / 2f
        ring.x = x - setengah
        ring.y = y - setengah
        ring.alpha = 1f
        ring.isVisible = true
        ring.animate().alpha(0f).setDuration(700).setStartDelay(600).withEndAction {
            ring.isVisible = false
        }.start()
    }

    // ================= Slider =================

    private fun syncSlidersFromState() {
        isUpdatingUi = true

        binding.isoSlider.valueFrom = 0f
        binding.isoSlider.valueTo = 1000f
        binding.isoSlider.value = isoToSlider(iso)
        binding.isoValue.text = "ISO $iso"

        binding.shutterSlider.valueFrom = 0f
        binding.shutterSlider.valueTo = 1000f
        binding.shutterSlider.value = shutterToSlider(shutterNs)
        binding.shutterValue.text = formatShutter(shutterNs)

        val terdukung = minFocusDistance > 0f
        val maxFocus = if (terdukung) minFocusDistance else 1f
        binding.focusSlider.valueFrom = 0f
        binding.focusSlider.valueTo = maxFocus
        binding.focusSlider.value = focusDiopter.coerceIn(0f, maxFocus)
        binding.focusValue.text = formatFocus(focusDiopter)

        binding.focusSlider.isEnabled = terdukung

        isUpdatingUi = false
    }

    private fun isoToSlider(nilai: Int): Float {
        val min = isoRange.lower.toDouble()
        val max = isoRange.upper.toDouble()
        if (max <= min) return 0f
        val t = ln(nilai.coerceIn(isoRange.lower, isoRange.upper).toDouble() / min) / ln(max / min)
        return (t * 1000.0).toFloat().coerceIn(0f, 1000f)
    }

    private fun sliderToIso(v: Float): Int {
        val min = isoRange.lower.toDouble()
        val max = isoRange.upper.toDouble()
        if (max <= min) return isoRange.lower
        val t = (v / 1000.0).coerceIn(0.0, 1.0)
        return (min * (max / min).pow(t)).roundToInt().coerceIn(isoRange.lower, isoRange.upper)
    }

    private fun shutterToSlider(ns: Long): Float {
        val min = shutterRange.lower.toDouble().coerceAtLeast(1.0)
        val max = shutterRange.upper.toDouble()
        if (max <= min) return 0f
        val t = ln(ns.coerceIn(shutterRange.lower, shutterRange.upper).toDouble() / min) / ln(max / min)
        return (t * 1000.0).toFloat().coerceIn(0f, 1000f)
    }

    private fun sliderToShutter(v: Float): Long {
        val min = shutterRange.lower.toDouble().coerceAtLeast(1.0)
        val max = shutterRange.upper.toDouble()
        if (max <= min) return shutterRange.lower
        val t = (v / 1000.0).coerceIn(0.0, 1.0)
        return (min * (max / min).pow(t)).toLong().coerceIn(shutterRange.lower, shutterRange.upper)
    }

    private fun clampShutter(ns: Long): Long = ns.coerceIn(shutterRange.lower, shutterRange.upper)

    private fun formatShutter(ns: Long): String {
        return when {
            ns >= 1_000_000_000L -> String.format(Locale.US, "%.1f s", ns / 1_000_000_000.0)
            ns >= 1_000_000L -> String.format(Locale.US, "1/%.0f s", 1_000_000_000.0 / ns)
            else -> String.format(Locale.US, "%.1f ms", ns / 1_000_000.0)
        }
    }

    private fun formatFocus(diopter: Float): String {
        if (diopter <= 0.0001f) return "∞"
        val meter = 1f / diopter
        return if (meter >= 1f) String.format(Locale.US, "%.1f m", meter)
        else String.format(Locale.US, "%.0f cm", meter * 100)
    }

    // ================= Lifecycle =================

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val rotation = binding.previewView.display?.rotation ?: Surface.ROTATION_0
        preview?.targetRotation = rotation
        imageCapture?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        zoomAnimator?.cancel()
        zoomAnimator = null
        recording?.stop()
        super.onDestroy()
    }
}
