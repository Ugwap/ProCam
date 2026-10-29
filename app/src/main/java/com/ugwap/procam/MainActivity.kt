package com.ugwap.procam

import android.Manifest
import android.content.ContentValues
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraProvider: ProcessCameraProvider

    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var mode = Mode.PHOTO
    private var proEnabled = false
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

    // FPS: null = biarkan kamera memilih (auto)
    private var fpsRange: android.util.Range<Int>? = null
    private val fpsOptions = mutableListOf<android.util.Range<Int>>()
    private var fpsInitialized = false

    private var isUpdatingUi = false
    private var isBusy = false

    private val uiExecutor by lazy { ContextCompat.getMainExecutor(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var recordStartedAt = 0L

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

    private fun setupUi() {
        binding.gridOverlay.gridEnabled = gridOn
        setChipActive(binding.btnGrid, gridOn)
        setChipActive(binding.btnFoto, true)
        setChipActive(binding.btnVideo, false)
        setChipActive(binding.btnPro, false)
        updateFlashChip()
        updateWatermarkChip()
        updateFpsChip()

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

        binding.btnFoto.setOnClickListener { switchMode(Mode.PHOTO) }
        binding.btnVideo.setOnClickListener { switchMode(Mode.VIDEO) }

        binding.btnPro.setOnClickListener {
            proEnabled = !proEnabled
            binding.proPanel.isVisible = proEnabled
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
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            fpsInitialized = false
            bindUseCases()
        }

        binding.btnZoom1.setOnClickListener { setZoom(1f) }
        binding.btnZoom2.setOnClickListener { setZoom(2f) }
        binding.btnZoom5.setOnClickListener { setZoom(5f) }

        binding.shutterRing.setOnClickListener { onShutter() }
        binding.shutterInner.setOnClickListener { onShutter() }

        setupProSliders()
    }

    private fun setupProSliders() {
        binding.isoSlider.addOnChangeListener { _, value, _ ->
            if (isUpdatingUi) return@addOnChangeListener
            iso = sliderToIso(value)
            binding.isoValue.text = "ISO $iso"
            updateHud()
        }
        binding.isoSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                if (proEnabled) bindUseCases()
            }
        })

        binding.shutterSlider.addOnChangeListener { _, value, _ ->
            if (isUpdatingUi) return@addOnChangeListener
            shutterNs = sliderToShutter(value)
            binding.shutterValue.text = formatShutter(shutterNs)
            updateHud()
        }
        binding.shutterSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                if (proEnabled) bindUseCases()
            }
        })

        binding.focusSlider.addOnChangeListener { _, value, _ ->
            if (isUpdatingUi) return@addOnChangeListener
            focusDiopter = value
            binding.focusValue.text = formatFocus(focusDiopter)
            updateHud()
        }
        binding.focusSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                if (proEnabled) bindUseCases()
            }
        })
    }

    private fun setChipActive(view: TextView, active: Boolean) {
        view.setBackgroundResource(if (active) R.drawable.bg_chip_on else R.drawable.bg_chip)
        view.setTextColor(if (active) 0xFF111111.toInt() else Color.WHITE)
    }

    private fun updateFlashChip() {
        binding.btnFlash.text = if (flashOn) "Flash ON" else "Flash"
        setChipActive(binding.btnFlash, flashOn)
    }

    private fun updateWatermarkChip() {
        binding.btnWatermark.text = if (watermarkOn) "WM ON" else "WM"
        setChipActive(binding.btnWatermark, watermarkOn)
    }

    private fun updateFpsChip() {
        binding.btnFps.text = fpsRange?.let { "FPS ${it.upper}" } ?: "FPS auto"
        setChipActive(binding.btnFps, fpsRange != null)
    }

    private fun switchMode(newMode: Mode) {
        if (mode == newMode) return
        if (recording != null) stopRecording()
        mode = newMode
        setChipActive(binding.btnFoto, mode == Mode.PHOTO)
        setChipActive(binding.btnVideo, mode == Mode.VIDEO)
        binding.zoomRow.isVisible = mode == Mode.PHOTO
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
                readHardwareInfo()
                bindUseCases()
            } catch (e: Exception) {
                Log.e(tag, "Gagal membuka kamera", e)
                Toast.makeText(this, "Kamera tidak dapat dibuka: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, uiExecutor)
    }

    private fun readHardwareInfo() {
        val info = cameraSelector.filter(cameraProvider.availableCameraInfos).firstOrNull() ?: return
        val c2 = Camera2CameraInfo.from(info)

        isoRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: android.util.Range(100, 3200)
        shutterRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: android.util.Range(1_000L, 500_000_000L)
        minFocusDistance = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

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

        val previewBuilder = Preview.Builder().setTargetRotation(rotation)
        fpsRange?.let { previewBuilder.setTargetFrameRate(it) }
        if (proEnabled) applyManualOptions(Camera2Interop.Extender(previewBuilder))

        val useCases = mutableListOf<UseCase>()
        val newPreview = previewBuilder.build()
        preview = newPreview
        useCases += newPreview

        if (mode == Mode.PHOTO) {
            val builder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                .setTargetRotation(rotation)

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

            maxZoom = bound.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
            binding.btnZoom5.isVisible = maxZoom >= 5f
            binding.btnZoom2.isVisible = maxZoom >= 2f
            updateZoomChips(1f)

            bound.cameraInfo.zoomState.observe(this) { state ->
                if (state != null) updateZoomChips(state.zoomRatio)
            }

            updateHud()
        } catch (e: Exception) {
            Log.e(tag, "Gagal bind use case", e)
            Toast.makeText(this, "Konfigurasi kamera gagal: ${e.message}", Toast.LENGTH_LONG).show()
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

    private fun setZoom(ratio: Float) {
        val target = ratio.coerceIn(1f, maxZoom)
        camera?.cameraControl?.setZoomRatio(target)
    }

    private fun updateZoomChips(current: Float) {
        setChipActive(binding.btnZoom1, current < 1.8f)
        setChipActive(binding.btnZoom2, current >= 1.8f && current < 3.5f)
        setChipActive(binding.btnZoom5, current >= 3.5f)
    }

    private fun updateHud() {
        val resolusi = imageCapture?.resolutionInfo?.resolution?.let { "${it.width}x${it.height}" }
        val fpsText = fpsRange?.let { "â‰¤${it.upper}fps" } ?: "auto"
        val proText = if (proEnabled) "ISO $iso Â· ${formatShutter(shutterNs)}" else "AUTO"
        binding.hudText.text = listOfNotNull(resolusi, fpsText, proText).joinToString(" Â· ")
    }

    private fun showFpsDialog() {
        if (fpsOptions.isEmpty()) {
            Toast.makeText(this, "Daftar FPS belum tersedia.", Toast.LENGTH_SHORT).show()
            return
        }

        val label = mutableListOf("Auto (bawaan kamera)")
        fpsOptions.forEach { r -> label.add("${r.lower}â€“${r.upper} fps") }

        val checked = if (fpsRange == null) 0 else fpsOptions.indexOf(fpsRange) + 1

        AlertDialog.Builder(this)
            .setTitle("Target FPS")
            .setSingleChoiceItems(label.toTypedArray(), checked) { dialog, which ->
                fpsRange = if (which == 0) null else fpsOptions[which - 1]
                updateFpsChip()
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
                if (watermarkOn && uri != null) {
                    Thread { terapkanWatermark(uri) }.start()
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

    private fun terapkanWatermark(uri: Uri) {
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

            val bmp = if (rotasi != 0f) {
                val m = Matrix().apply { postRotate(rotasi) }
                Bitmap.createBitmap(asli, 0, 0, asli.width, asli.height, m, true)
            } else {
                asli
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
            kanvas.drawText("ProCam Â· $waktu", bmp.width * 0.04f, bmp.height * 0.96f, cat)

            contentResolver.openOutputStream(uri, "w")?.use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
            }

            if (bmp !== asli) bmp.recycle()
            asli.recycle()

            runOnUiThread {
                Toast.makeText(this, "Foto + watermark tersimpan di DCIM/ProCam", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(tag, "Watermark gagal", e)
            runOnUiThread {
                Toast.makeText(this, "Foto tersimpan, namun watermark gagal.", Toast.LENGTH_SHORT).show()
            }
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
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                setZoom(current * detector.scaleFactor)
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
        if (diopter <= 0.0001f) return "âˆž"
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
        recording?.stop()
        super.onDestroy()
    }
}
