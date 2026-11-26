package com.example.pokemonivreader

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.Gravity
import android.view.MotionEvent
import android.provider.Settings
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.example.pokemonivreader.catchassist.CatchLiveThrow
import com.example.pokemonivreader.catchassist.CatchLocalStore
import com.example.pokemonivreader.catchassist.CatchPointerCrosshair
import com.example.pokemonivreader.catchassist.CatchThrowExample
import com.example.pokemonivreader.catchassist.CatchThrowRange
import com.example.pokemonivreader.overlayqueue.GoButtonLearningMenu
import com.example.pokemonivreader.overlayqueue.GoButtonTemplateStore
import com.example.pokemonivreader.overlayqueue.GoTextInputAccessibilityService
import com.example.pokemonivreader.overlayqueue.ReaderOverlayVisibility
import com.example.pokemonivreader.readerquickmenu.ReaderQuickMenuPopup
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.abs

/**
 * Phase 3D: one-tap fast scan with a persistent floating button.
 * The overlay stays visible, but scanning is blocked if it overlaps the panel.
 * The notification's legacy capture action still saves a PNG for manual review;
 * floating-button scanning keeps no screenshot, only the IV result.
 */
class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.example.pokemonivreader.START_CAPTURE"
        const val ACTION_SHOW_OVERLAY = "com.example.pokemonivreader.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.example.pokemonivreader.HIDE_OVERLAY"
        @Volatile var isSessionActive = false
            private set
        const val ACTION_CAPTURE = "com.example.pokemonivreader.CAPTURE_ONE"
        const val ACTION_STOP = "com.example.pokemonivreader.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        const val CAPTURE_DIR = "direct_captures"
        const val MAX_CAPTURES = 20
        private const val NOTIFICATION_ID = 1815
        private const val CHANNEL_ID = "iv_screen_capture"
    }

    private val main = Handler(Looper.getMainLooper())
    private val capturePending = AtomicBoolean(false)
    private class ThrowRecording(
        val window: () -> Rect?,
        val bounds: Rect,
        val tracker: CatchLiveThrow,
        val startsAt: Long,
    ) {
        // Written only on the capture thread.
        var completed = false
    }
    @Volatile private var throwRecording: ThrowRecording? = null
    private var recordedThrow: CatchThrowExample? = null
    private var throwDialog: AlertDialog? = null

    private data class SharedTeachingFrame(
        val bounds: Rect, val after: Long, val result: (Bitmap?) -> Unit,
    )
    private val teachingFrame = AtomicReference<SharedTeachingFrame?>()
    private val shadowBurst = ShadowBurst()
    private val cpFrames = CpFrameBuffer()
    private var reviewGeneration = 0L
    private val appraisalReview by lazy { AppraisalReviewOverlay(this) {
        reviewGeneration++
        captureGate.clear(); cpFrames.clear(); previousScene = null
    } }
    private val captureGate = CaptureGate()
    private val pipeline = ScanPipeline(4)
    private data class AutoCapture(val recordId: String, val done: (OverlayIvProcessor.Outcome) -> Unit)
    private val automaticCaptures = java.util.concurrent.ConcurrentHashMap<Long, AutoCapture>()
    @Volatile private var autoReading = false
    @Volatile private var automaticFrame: ReaderAutoScan.Frame? = null
    private var autoScan: ReaderAutoScan? = null
    private val readerDiagnostics by lazy { ReaderDiagnostics(this) }
    private var readerControl: com.example.pokemonivreader.overlayqueue.GoTextInputAccessibilityService.ReaderControl? = null
    private fun finishAutomaticCapture(id: Long, outcome: OverlayIvProcessor.Outcome) {
        main.post { automaticCaptures.remove(id)?.done?.invoke(outcome) }
    }
    @Volatile private var activeCaptureId = 0L
    @Volatile private var expectedScene: CaptureGate.Signature? = null
    @Volatile private var overlayVisible = false
    private var lastObservedAt = 0L
    @Volatile private var previousScene: CaptureGate.Signature? = null
    private var panelCoordinates: DoubleArray? = null
    private var panelRaw: String? = null
    private var reviewCount=0
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var hasStopped = false
    private var captureCount = 0
    private var scheduled = false
    // Keep the overlay visible during capture. Do not scan if it overlaps
    // the saved appraisal-panel rectangle; this avoids contaminated values.
    private var overlay: TextView? = null
    private var shadowOverlayRoot: android.widget.LinearLayout? = null
    private var readerQuickMenu: ReaderQuickMenuPopup? = null
    private var learningPopup: PopupMenu? = null
    private var learningDialog: AlertDialog? = null
    private var learningRequestGeneration = 0
    @Volatile private var activeShadowMode = ShadowRecognizer.Mode.AUTO
    private var overlayLayout: WindowManager.LayoutParams? = null
    @Volatile private var captureByOverlay = false
    private val scanInProgress = AtomicBoolean(false)
    @Volatile private var scanStartedAt = 0L
    private val scanTimeout = Runnable {
        val id=activeCaptureId
        if(id!=0L) {
            releaseCapture(id)
            pipeline.finish(id)
            runCatching { readerDiagnostics.captureFailure(ReaderDiagnostics.CaptureFailure.IMAGE_TIMEOUT,autoReading,SystemClock.elapsedRealtime()-scanStartedAt) }
            finishAutomaticCapture(id, OverlayIvProcessor.Outcome("画像未確保・再読取", false))
            if(pipeline.isNewest(id)) showScanResult("画像未確保・再読取",false)
        }
    }

    @Synchronized private fun releaseCapture(id:Long) {
        if(activeCaptureId!=id) return
        activeCaptureId=0L
        capturePending.set(false)
        captureByOverlay=false
        scanInProgress.set(false)
        expectedScene=null
        main.removeCallbacks(scanTimeout)
    }


    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Android can stop projection when the user revokes access, locks the
            // device, or another capture session begins.
            closeProjection(stopProjection = false)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var readerOverlayRequested = false
    private var readerPosition: Pair<Int, Int>? = null
    private val searchVisibilityListener: (Boolean) -> Unit = { active ->
        if (active) removeOverlay()
        else if (readerOverlayRequested && projection != null) showOverlay()
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        com.example.pokemonivreader.overlayqueue.SearchOverlayVisibility.observe(searchVisibilityListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (projection == null) {
                    val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                    @Suppress("DEPRECATION")
                    val data = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    } else {
                        intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                    }
                    if (code == 0 || data == null) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    // Required order for targetSdk 34+: consent -> foreground service
                    // of type mediaProjection -> getMediaProjection -> createVirtualDisplay.
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            startForeground(
                                NOTIFICATION_ID,
                                notification("画面取得中。Pokémon GOで通知から撮影できます"),
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                            )
                        } else {
                            startForeground(NOTIFICATION_ID, notification("画面取得中"))
                        }
                        startProjection(code, data)
                    } catch (e: Exception) {
                        getSharedPreferences("pokemon_iv_reader", Context.MODE_PRIVATE)
                            .edit().putString("capture_error", e.javaClass.simpleName + ": " + (e.message ?: ""))
                            .apply()
                        closeProjection(stopProjection = true)
                        stopSelf()
                    }
                }
            }
            ACTION_SHOW_OVERLAY -> {
                if(projection!=null) showOverlay() else stopSelf()
            }
            ACTION_HIDE_OVERLAY -> { readerOverlayRequested = false; removeOverlay() }
            ACTION_CAPTURE -> {
                if (projection != null && throwRecording == null && !autoReading && !scheduled && !capturePending.get() &&
                    !GoTextInputAccessibilityService.isCatchTrialRunning()) {
                    scheduled = true
                    updateNotification("2秒後に撮影します。通知を閉じてゲーム画面に戻ってね")
                    main.postDelayed({
                        scheduled = false
                        if (projection != null) {
                            capturePending.set(true)
                            // Prevent a permanent wait if the virtual display stops producing frames.
                            main.postDelayed({
                                if (capturePending.getAndSet(false)) {
                                    updateNotification("撮影できませんでした。画面取得を開始し直してね")
                                }
                            }, 4500L)
                        }
                    }, 2200L)
                }
            }
            ACTION_STOP -> {
                closeProjection(stopProjection = true)
                stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val current = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("画面取得の許可を取得できませんでした")
        projection = current
        current.registerCallback(projectionCallback, main)

        @Suppress("DEPRECATION")
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val size = Point().also { windowManager.defaultDisplay.getRealSize(it) }
        val width = size.x
        val height = size.y
        require(width > 0 && height > 0) { "Invalid display size" }

        val dpi = resources.displayMetrics.densityDpi
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader = imageReader
        val thread = HandlerThread("PokemonIVScreenCapture").apply { start() }
        captureThread = thread
        imageReader.setOnImageAvailableListener({ source ->
            val image=try { source.acquireLatestImage() } catch (_:Exception){null}
            if(image!=null) try {
                val now=SystemClock.elapsedRealtime()
                throwRecording?.let { recording ->
                    if (!recording.completed && now >= recording.startsAt) readLiveThrow(image, recording)
                    return@setOnImageAvailableListener
                }
                val teaching = teachingFrame.get()
                if (teaching != null && now >= teaching.after && teachingFrame.compareAndSet(teaching, null)) {
                    val rect = teaching.bounds
                    val bitmap = runCatching {
                        require(Rect(0, 0, image.width, image.height).contains(rect))
                        OverlayIvProcessor.copyRegion(image, rect.left, rect.top, rect.width(), rect.height())
                    }.getOrNull()
                    main.post {
                        if (hasStopped) {
                            bitmap?.recycle()
                            teaching.result(null)
                        } else teaching.result(bitmap)
                    }
                    return@setOnImageAvailableListener
                }
                var signature:CaptureGate.Signature?=null
                if(overlayVisible && !appraisalReview.isShowing && (now-lastObservedAt>=40L || captureByOverlay)) {
                    refreshPanel()
                    signature=OverlayIvProcessor.sceneSignature(image,panelCoordinates,includeCp=false)
                    if(autoReading) signature=ReaderSceneFingerprint.stable(signature)
                    if(previousScene?.same(signature)!=true) shadowBurst.reset()
                    if(now-lastObservedAt>=40L) {
                        captureGate.observe(signature,now)
                        lastObservedAt=now
                        if(shadowBurst.needsSample(now)) shadowBurst.offer(now,OverlayIvProcessor.inspectShadowFrame(image))
                    }
                    cpFrames.offer(now,signature) { OverlayIvProcessor.cpFrame(image) }
                    previousScene=signature
                    if (autoReading && (automaticFrame?.at?.let { now-it >= 150L } != false)) {
                        automaticFrame = ReaderAutoScan.Frame(signature, now,
                            OverlayIvProcessor.hasAppraisalBars(this, image, panelCoordinates))
                    }
                }
                if(capturePending.compareAndSet(true,false)) {
                    if(captureByOverlay) {
                        val id=activeCaptureId
                        val started=scanStartedAt
                        val expected=expectedScene
                        val automatic = automaticCaptures[id]
                        if(id!=0L && expected!=null && signature!=null && expected.same(signature)) {
                            val extraFrames = cpFrames.snapshot(now,signature)
                            val enoughFrames = extraFrames.size >= 2 || now-started >= 600L
                            extraFrames.forEach { it.recycle() }
                            if(!enoughFrames) {
                                capturePending.set(true)
                                return@setOnImageAvailableListener
                            }
                            val samples=shadowBurst.latest()
                            val capturedShadowMode = activeShadowMode
                            try {
                                OverlayIvProcessor.process(this,image,samples,cpFrames=cpFrames.snapshot(now,signature),
                                    scanModeOverride=capturedShadowMode,
                                    recordIdOverride=automatic?.recordId,
                                    onCaptured={ releaseCapture(id) },
                                    onProgress={ progress -> main.post {
                                        if(!hasStopped && pipeline.isNewest(id)) showScanResult(progress,now-started)
                                    }}
                                ) { outcome ->
                                    runCatching { readerDiagnostics.captureResult(outcome.phase,SystemClock.elapsedRealtime()-started,automatic!=null,
                                        cpSeen=outcome.displayCp!=null,ivSeen=outcome.displayIv!=null,
                                        cpAmbiguous=outcome.cpCandidates.size>1,formAmbiguous=outcome.formCandidates.size>1) }
                                    releaseCapture(id)
                                    pipeline.finish(id)
                                    main.post {
                                        if(!hasStopped) {
                                            if(outcome.phase==OverlayIvProcessor.Phase.REVIEW || outcome.phase==OverlayIvProcessor.Phase.FAILED) reviewCount++
                                            if(pipeline.isNewest(id)) {
                                                showScanResult(outcome,SystemClock.elapsedRealtime()-started)
                                                if(automatic == null && outcome.recordId.isNotBlank() && overlayVisible &&
                                                    previousScene?.let { expected.same(it) } == true) reviewInAppraisal(outcome)
                                            }
                                            else refreshReviewBadge()
                                            finishAutomaticCapture(id, outcome)
                                        }
                                    }
                                }
                            } catch(e:Exception) {
                                releaseCapture(id);pipeline.finish(id)
                                runCatching { readerDiagnostics.captureFailure(ReaderDiagnostics.CaptureFailure.PROCESS_EXCEPTION,automatic!=null,SystemClock.elapsedRealtime()-started) }
                                finishAutomaticCapture(id, OverlayIvProcessor.Outcome("解析失敗・再読取", false))
                                main.post { if(!hasStopped && pipeline.isNewest(id)) showScanResult("解析失敗・再読取",false) }
                            }
                        } else {
                            releaseCapture(id);pipeline.finish(id)
                            runCatching { readerDiagnostics.captureFailure(ReaderDiagnostics.CaptureFailure.SCREEN_CHANGED,automatic!=null,SystemClock.elapsedRealtime()-started) }
                            finishAutomaticCapture(id, OverlayIvProcessor.Outcome("切替中・再読取", false))
                            main.post { if(!hasStopped && pipeline.isNewest(id)) showScanResult("切替中・再読取",false) }
                        }
                    } else saveCapture(image)
                }
            } catch(e:Exception) {
                val id=activeCaptureId
                if(id!=0L) {releaseCapture(id);pipeline.finish(id)}
                if(id!=0L)runCatching { readerDiagnostics.captureFailure(ReaderDiagnostics.CaptureFailure.IMAGE_ERROR,autoReading,SystemClock.elapsedRealtime()-scanStartedAt) }
                finishAutomaticCapture(id, OverlayIvProcessor.Outcome("取得失敗・再読取", false))
                main.post { if(!hasStopped && (id==0L || pipeline.isNewest(id))) showScanResult("取得失敗・再読取",false) }
            } finally {
                image.close()
                if(!overlayVisible) cpFrames.clear()
            }
        }, Handler(thread.looper))

        virtualDisplay = current.createVirtualDisplay(
            "PokemonIVReaderCapture", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, main
        )
        getSharedPreferences("pokemon_iv_reader", Context.MODE_PRIVATE)
            .edit().remove("capture_error").apply()
        isSessionActive=true
        OverlayIvProcessor.worker.execute { ScreenTextRecognizer.warmUp() }
        showOverlay()
        updateNotification("IVボタンで自動登録。終了は通知から操作")
    }

    private fun refreshPanel() {
        val raw=getSharedPreferences("pokemon_iv_reader",Context.MODE_PRIVATE).getString("crop_template_v1",null)
        if(raw==panelRaw)return
        panelRaw=raw
        panelCoordinates = try {
            raw?.let { value -> JSONObject(value).let { cfg ->
                doubleArrayOf(cfg.getDouble("x1"),cfg.getDouble("y1"),cfg.getDouble("x2"),cfg.getDouble("y2"))
            }}?.takeIf { values -> values.all{it.isFinite() && it in 0.0..1.0} }?.let { v ->
                doubleArrayOf(min(v[0],v[2]),min(v[1],v[3]),max(v[0],v[2]),max(v[1],v[3]))
            }
        } catch (_:Exception) { null }
        captureGate.clear();shadowBurst.reset();cpFrames.clear();previousScene=null
    }

    /** Movable CP / IV / status bubble. */
    private fun showOverlay() {
        readerOverlayRequested = true
        if (com.example.pokemonivreader.overlayqueue.SearchOverlayVisibility.active) return
        if (overlay != null || !Settings.canDrawOverlays(this)) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val button = TextView(this).apply {
            text = "CP —\nIV —\nタップで読取"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            minWidth = (90 * density).toInt()
            minHeight = (70 * density).toInt()
            background = GradientDrawable().apply {
                setColor(Color.rgb(66, 84, 76))
                cornerRadius = 28f * density
            }
            contentDescription = "個体値を読み取って登録"
        }
        val params = WindowManager.LayoutParams(
            (110 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = readerPosition?.first ?: (12 * density).toInt()
            y = readerPosition?.second ?: ((reader?.height ?: resources.displayMetrics.heightPixels) * 0.37).toInt()
        }
        val quickMenu = ReaderQuickMenuPopup(
            this,
            autoRead = { startAutomaticReading() },
            resumeAutoRead = { startAutomaticReading(resume = true) },
            automation = { startAutomaticReading(automation = true) },
            resumeAutomation = { startAutomaticReading(automation = true, resume = true) },
            setCatchEnabled = ::setCatchTrialEnabled,
            teachCatchSample = ::teachCatchSample,
            liveThrow = {
                when {
                    throwRecording != null -> "実投球の記録を中止" to { cancelLiveThrow("実投球の記録を中止しました") }
                    recordedThrow != null -> "記録した投球を保存" to ::confirmLiveThrow
                    else -> "実投球を記録" to ::startLiveThrow
                }
            },
            learnButtons = ::openButtonLearning,
        )
        val shadowRoot = ReaderShadowControls.wrap(this, button) { trigger ->
            quickMenu.toggle(trigger)
        }
        var startRawX = 0f
        var startRawY = 0f
        var windowX = 0
        var windowY = 0
        var moved = false
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    windowX = params.x
                    windowY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (abs(dx) > 12f * density || abs(dy) > 12f * density) moved = true
                    if (moved) {
                        params.x = (windowX + dx).toInt().coerceAtLeast(0)
                        params.y = (windowY + dy).toInt().coerceAtLeast(0)
                        try { wm.updateViewLayout(shadowRoot, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && !autoReading) requestOverlayCapture()
                    true
                }
                else -> true
            }
        }
        try {
            wm.addView(shadowRoot, params)
            shadowOverlayRoot = shadowRoot
            readerQuickMenu = quickMenu
            overlayVisible = true
            overlay = button
            overlayLayout = params
            ReaderOverlayVisibility.register(::pauseReaderOverlayForLearning)
        } catch (e: Exception) {
            updateNotification("フロート表示失敗。許可を確認してください")
        }
    }

    private fun startLiveThrow() {
        val imageReader = reader
        if (imageReader == null || throwRecording != null || autoReading || scheduled ||
            capturePending.get() || scanInProgress.get() || pipeline.size() != 0 || appraisalReview.isShowing) {
            throwHint("読み取りを終え、画面共有を開始してから記録してください")
            return
        }
        val window = GoTextInputAccessibilityService.beginManualThrowRecording()
        val bounds = window?.invoke()
        if (window == null || bounds == null || !Rect(0, 0, imageReader.width, imageReader.height).contains(bounds) ||
            bounds.width() >= bounds.height()) {
            throwHint("Pokémon GOの捕獲画面を縦向き・全画面で開いてください")
            return
        }
        val recording = ThrowRecording(window, bounds,
            CatchLiveThrow(bounds.left, bounds.top, bounds.width(), bounds.height()),
            SystemClock.elapsedRealtime() + 550L)
        throwRecording = recording
        overlay?.text = "実投球を記録中\n⋯で中止"
        updateNotification("実投球を記録中。ポインタの位置をオンにし、指1本で投げてください")
        throwHint("ポインタの位置をオンにして、ボールを指1本で投げてください")
        monitorLiveThrow(recording)
    }

    private fun monitorLiveThrow(recording: ThrowRecording) {
        if (throwRecording !== recording) return
        if (recording.window() != recording.bounds) {
            cancelLiveThrow("Pokémon GOの画面が切り替わりました")
        } else if (SystemClock.elapsedRealtime() - recording.startsAt >= 12_000L) {
            val message = if (recording.tracker.sawPointer) "ボールを押した位置から投球を記録できませんでした"
                else "指の位置が画面共有に映りません。開発者向けオプションの「ポインタの位置」と画面全体の共有を確認してください"
            cancelLiveThrow(message)
        } else main.postDelayed({ monitorLiveThrow(recording) }, 140L)
    }

    private fun readLiveThrow(image: Image, recording: ThrowRecording) {
        val plane = image.planes.firstOrNull() ?: return
        if (plane.pixelStride != 4) return
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val reading = CatchPointerCrosshair.read(image.width, image.height) { x, y ->
            val index = y * rowStride + x * 4
            index + 2 < buffer.limit() && CatchPointerCrosshair.isBlue(
                buffer.get(index).toInt() and 255, buffer.get(index + 1).toInt() and 255,
                buffer.get(index + 2).toInt() and 255)
        }
        val result = recording.tracker.sample(image.timestamp / 1_000_000L, reading) ?: return
        recording.completed = true
        main.post {
            if (throwRecording !== recording) return@post
            when {
                recording.window() != recording.bounds -> cancelLiveThrow("Pokémon GOの画面が切り替わりました")
                result is CatchLiveThrow.Result.Recorded -> {
                    recordedThrow = result.example
                    cancelLiveThrow("投球を記録しました。命中を確認し、⋯から保存できます")
                }
                else -> cancelLiveThrow("軌道が途切れたか、カーブの回転を確認できませんでした。指1本で投げ直してください")
            }
        }
    }

    private fun cancelLiveThrow(message: String? = null) {
        if (throwRecording == null) return
        throwRecording = null
        overlay?.text = if (recordedThrow != null) "実投球を記録済み\n⋯で保存" else "CP —\nIV —\nタップで読取"
        updateNotification("IVボタンで自動登録。終了は通知から操作")
        if (message != null) throwHint(message)
    }

    private fun confirmLiveThrow() {
        val example = recordedThrow ?: return
        val ranges = CatchThrowRange.entries
        val dialog = AlertDialog.Builder(this)
            .setTitle("命中した投球の距離を選んで保存")
            .setItems(ranges.map { it.label }.toTypedArray()) { _, index ->
                val range = ranges[index]
                val store = CatchLocalStore(this)
                val saved = runCatching {
                    val profile = store.profile()
                    store.save(profile.copy(throwExamples = profile.throwExamples + (range to example)))
                }.getOrDefault(false)
                if (saved) clearRecordedThrow()
                throwHint(if (saved) "${range.label}の投球を端末に保存しました" else "投球を保存できませんでした")
            }
            .setNeutralButton("破棄") { _, _ -> clearRecordedThrow() }
            .setNegativeButton("戻る", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.setOnDismissListener { throwDialog = null }
        if (runCatching { dialog.show() }.isSuccess) throwDialog = dialog
        else throwHint("投球を保存する画面を開けませんでした")
    }

    private fun clearRecordedThrow() {
        recordedThrow = null
        overlay?.text = "CP —\nIV —\nタップで読取"
    }

    private fun throwHint(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun teachCatchSample() {
        if (throwRecording != null || autoReading || scheduled || capturePending.get() || scanInProgress.get() ||
            pipeline.size() != 0 || appraisalReview.isShowing || !GoTextInputAccessibilityService.teachCatchSample(::captureSharedTeachingFrame)) {
            Toast.makeText(this, "読み取りを止め、Pokémon GOで教えたい画面を表示してください",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun captureSharedTeachingFrame(bounds: Rect, result: (Bitmap?) -> Unit) {
        val restore = if (projection != null) pauseReaderOverlayForCatchGesture() else null
        if (restore == null) { result(null); return }
        val request = SharedTeachingFrame(Rect(bounds), SystemClock.elapsedRealtime() + 110L) { bitmap ->
            restore()
            result(bitmap)
        }
        teachingFrame.set(request)
        main.postDelayed({
            if (teachingFrame.compareAndSet(request, null)) request.result(null)
        }, 700L)
    }

    private fun setCatchTrialEnabled(enabled: Boolean) {
        val service = GoTextInputAccessibilityService.Companion
        if (!enabled) {
            service.stopCatchTrial()
            return
        }
        if (service.isCatchTrialRunning()) return
        val message = when {
            projection == null || throwRecording != null || autoReading || scheduled || capturePending.get() || scanInProgress.get() ||
                pipeline.size() != 0 || appraisalReview.isShowing -> "読み取りや確認を終えてから捕獲をオンにしてください"
            !service.isAvailable() -> "読み取りアプリのユーザー補助を有効にしてください"
            service.startCatchTrial(::pauseReaderOverlayForCatchGesture) -> "クイック捕獲をオンにしました"
            else -> service.catchTrialStatus()
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun pauseReaderOverlayForCatchGesture(): (() -> Unit)? {
        val root = shadowOverlayRoot ?: return null
        val params = overlayLayout ?: return null
        if (readerQuickMenu?.isShowing == true || !root.isAttachedToWindow) return null
        val manager = getSystemService(WindowManager::class.java) ?: return null
        val flags = params.flags
        params.flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            manager.updateViewLayout(root, params)
            root.visibility = View.INVISIBLE
        } catch (_: Exception) {
            params.flags = flags
            return null
        }
        return {
            params.flags = flags
            if (shadowOverlayRoot === root && root.isAttachedToWindow) {
                root.visibility = View.VISIBLE
                runCatching { manager.updateViewLayout(root, params) }
            }
        }
    }

    private fun isLearningBusy(): Boolean =
        hasStopped || !overlayVisible || throwRecording != null || autoReading || scheduled ||
            capturePending.get() || scanInProgress.get() || pipeline.size() != 0 || appraisalReview.isShowing

    private fun showLearningFeedback(message: String) {
        main.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun showLearningDialog(dialog: AlertDialog) {
        learningDialog?.dismiss()
        learningDialog = dialog
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        dialog.setOnDismissListener { if (learningDialog === dialog) learningDialog = null }
        if (runCatching { dialog.show() }.isFailure) learningDialog = null
    }

    private fun openButtonLearning(anchor: View) {
        if (isLearningBusy()) {
            Toast.makeText(this, "読み取りや確認を終えてから学習を開いてください", Toast.LENGTH_SHORT).show()
            return
        }
        anchor.post {
            if (isLearningBusy() || !anchor.isAttachedToWindow) return@post
            GoButtonLearningMenu.show(
                context = this,
                anchor = anchor,
                isLocked = ::isLearningBusy,
                showDialog = ::showLearningDialog,
                onStatus = ::showLearningFeedback,
                onRename = { role ->
                    if (!GoTextInputAccessibilityService.renameLearned(role, ::showLearningFeedback)) {
                        showLearningFeedback("Androidのユーザー補助で「厳選テキスト入力」を有効にしてください")
                    }
                },
                requestLearning = ::requestButtonLearning,
                onMenuOpened = { popup ->
                    if (learningPopup !== popup) learningPopup?.dismiss()
                    learningPopup = popup
                },
            )
        }
    }

    private fun requestButtonLearning(role: GoButtonTemplateStore.Role? = null, replace: Int? = null,
                                      preview: Boolean = false) {
        val requestId = ++learningRequestGeneration
        main.postDelayed({
            if (requestId != learningRequestGeneration || isLearningBusy()) return@postDelayed
            if (!GoTextInputAccessibilityService.learn(role, replace, preview, ::showLearningFeedback)) {
                showLearningFeedback("Androidのユーザー補助で「厳選テキスト入力」を有効にしてください")
            }
        }, 250L)
    }

    private fun pauseReaderOverlayForLearning(): (() -> Unit)? {
        readerQuickMenu?.dismiss()
        learningPopup?.dismiss()
        learningPopup = null
        val root = shadowOverlayRoot ?: return null
        val params = overlayLayout ?: return null
        if (!root.isAttachedToWindow || root.visibility != View.VISIBLE) return null
        val manager = getSystemService(WindowManager::class.java) ?: return null
        val flags = params.flags
        params.flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            manager.updateViewLayout(root, params)
            root.visibility = View.INVISIBLE
        } catch (_: Exception) {
            params.flags = flags
            return null
        }
        return {
            params.flags = flags
            if (shadowOverlayRoot === root && root.isAttachedToWindow && !autoReading && overlayVisible) {
                root.visibility = View.VISIBLE
                runCatching { manager.updateViewLayout(root, params) }
            }
        }
    }

    /** One tap: capture immediately without removing or hiding the floating button. */
    @Synchronized private fun requestOverlayCapture(automatic: AutoCapture? = null) {
        fun rejected(message: String) {
            automatic?.done?.invoke(OverlayIvProcessor.Outcome(message, false))
        }
        readerQuickMenu?.dismiss() // Menu must never cover IV/CP frames.
        learningRequestGeneration++
        learningPopup?.dismiss(); learningPopup = null
        learningDialog?.dismiss(); learningDialog = null
        if (GoTextInputAccessibilityService.isCatchTrialRunning()) {
            Toast.makeText(this, "捕獲をオフにしてから読み取ってください", Toast.LENGTH_SHORT).show()
            rejected("クイック捕獲中")
            return
        }
        if(throwRecording != null || projection==null || appraisalReview.isShowing) { rejected("画面取得・確認画面の終了待ち"); return }
        if(scheduled || capturePending.get() || scanInProgress.get()) {
            overlay?.text="画像確保中\n少し待ってね"
            rejected("画像確保待ち")
            return
        }
        pipeline.reject()
        if(!autoReading && overlayOverlapsCrop()) {
            showScanResult("ボタンを読取範囲の外へ",false); rejected("読取範囲の重なり"); return
        }
        val now=SystemClock.elapsedRealtime()
        val scene=captureGate.forTap(now)
        if(scene==null) {
            showScanResult("切替中・再読取",false); rejected("切替中"); return
        }
        val id=pipeline.begin()
        if(id==null) {
            overlay?.text="解析中 ${pipeline.size()}件\n完了後に再タップ"
            rejected("解析待ち")
            return
        }
        cpFrames.clear() // A burst starts at this tap; never use frames of the previously displayed individual.
        activeShadowMode = ReaderShadowControls.mode(this)
        if (automatic != null) automaticCaptures[id] = automatic
        activeCaptureId=id
        expectedScene=scene.signature
        scanStartedAt=now
        scanInProgress.set(true)
        captureByOverlay=true
        overlay?.text="CP —\nIV —\n画像確保中"
        refreshReviewBadge()
        capturePending.set(true)
        main.postDelayed(scanTimeout,1500L)
    }

    private fun startAutomaticReading(automation:Boolean=false,resume:Boolean=false) {
        if (GoTextInputAccessibilityService.isCatchTrialRunning()) {
            Toast.makeText(this, "捕獲をオフにしてから読み取ってください", Toast.LENGTH_SHORT).show()
            return
        }
        if (throwRecording != null || autoReading || projection == null || pipeline.size() != 0 || appraisalReview.isShowing) {
            runCatching { readerDiagnostics.setupFailure(ReaderDiagnostics.SetupFailure.BUSY) }
            updateNotification("読取・確認が完了してから自動読み取りを開始してください"); return
        }
        refreshPanel()
        if (panelCoordinates == null) {
            runCatching { readerDiagnostics.setupFailure(ReaderDiagnostics.SetupFailure.PANEL_MISSING) }
            updateNotification("先に評価パネルの範囲を設定してください"); return
        }
        val checkpoint = ReaderScanCheckpoint(this, if (automation) "scan_checkpoint" else "manual_scan_checkpoint")
        val previous = if (resume) checkpoint.load() else null
        if (resume && previous == null) {
            updateNotification("再開できる読み取り履歴がありません")
            Toast.makeText(this, "再開できる読み取り履歴がありません", Toast.LENGTH_SHORT).show()
            return
        }
        val control = com.example.pokemonivreader.overlayqueue.GoTextInputAccessibilityService.beginReaderAutomation {
            autoScan?.cancel()
        }
        if (control == null) {
            runCatching { readerDiagnostics.setupFailure(ReaderDiagnostics.SetupFailure.ACCESSIBILITY_UNAVAILABLE) }
            updateNotification("GOを開き、ユーザー補助と停止ボタンの表示を確認してください"); return
        }
        val session=ReaderAutomationStore(this)
        if (automation && !resume) session.beginReading()
        if (!resume) checkpoint.clear()
        readerControl = control
        autoReading = true; automaticFrame = null
        captureGate.clear();previousScene=null;cpFrames.clear()
        // Keep MediaProjection sampling active while our own reader is invisible.
        shadowOverlayRoot?.visibility = android.view.View.INVISIBLE
        autoScan = ReaderAutoScan(main, object : ReaderAutoScan.Port {
            override fun restore() = previous
            override fun checkpoint(value: ReaderAutoScan.Checkpoint) = checkpoint.save(value)
            override fun saved(recordId:String) {
                if (automation) {
                    session.saved(recordId)
                    session.markHold(recordId, verified = true)
                }
            }
            override fun foreground() = projection != null && control.foreground()
            override fun frame() = automaticFrame
            override fun openAppraisal(result: (Boolean) -> Unit) = control.openAppraisal(result)
            override fun capture(recordId: String, result: (OverlayIvProcessor.Outcome) -> Unit) {
                requestOverlayCapture(AutoCapture(recordId, result))
            }
            override fun holdCurrent(expected: ReaderAutoScan.Frame, expectedCp: Int?,
                                     result: (com.example.pokemonivreader.overlayqueue.GoHoldWorkflow.Result) -> Unit) =
                control.holdCurrent(expectedCp, { after ->
                    automaticFrame?.takeIf {
                        it.at > after && SystemClock.elapsedRealtime() - it.at in 0..800 &&
                            (!it.appraisal || expected.signature.same(it.signature))
                    }?.appraisal
                }, result)
            override fun holdStatus(recordId: String, state: ReaderAutoScan.HoldState,
                                    result: com.example.pokemonivreader.overlayqueue.GoHoldWorkflow.Result?): Boolean {
                if (result == null) runCatching {
                    readerDiagnostics.holdResult(state, null, com.example.pokemonivreader.overlayqueue.GoLearningTarget.PENDING)
                }
                if (!AutoIvRecords.markReaderHold(this@ScreenCaptureService, recordId, state.name,
                        result?.stage?.name, result?.reason?.name)) return false
                return !automation || session.markHold(recordId, verified = state == ReaderAutoScan.HoldState.TAGGED)
            }
            override fun swipeLeft(result: (Boolean) -> Unit) = control.swipeLeft(result)
            override fun hint(message: String) { updateNotification(message) }
            override fun stopped(stage:ReaderAutoScan.Stage,reason:ReaderAutoScan.StopReason,saved:Int,retries:Int) {
                runCatching { readerDiagnostics.scanStopped(stage,reason,saved,retries) }
            }
        }) { message ->
            autoReading = false; autoScan = null; automaticFrame = null
            captureGate.clear();previousScene=null;cpFrames.clear()
            automaticCaptures.clear()
            readerControl?.close(); readerControl = null
            shadowOverlayRoot?.visibility = android.view.View.VISIBLE
            showScanResult(message, false)
            if(automation){
                session.pauseReading()
                // Only saved records in this batch are handed off; an unchanged display is
                // an end OR ambiguity, never proof that unread individuals were scanned.
                if(message.startsWith("左スワイプ後の移動なし")&&session.records().isNotEmpty())
                    ReaderWebTransfer.automate(this)
            }
        }.also { it.start() }
    }

    private fun overlayOverlapsCrop(): Boolean {
        val bubble = shadowOverlayRoot ?: return false
        val frameReader = reader ?: return false
        val raw = getSharedPreferences("pokemon_iv_reader", Context.MODE_PRIVATE)
            .getString("crop_template_v1", null) ?: return false
        val config = try { JSONObject(raw) } catch (_: Exception) { return false }
        val x1 = config.optDouble("x1", Double.NaN)
        val x2 = config.optDouble("x2", Double.NaN)
        val y1 = config.optDouble("y1", Double.NaN)
        val y2 = config.optDouble("y2", Double.NaN)
        if (listOf(x1, x2, y1, y2).any { !it.isFinite() || it !in 0.0..1.0 }) return false
        val panel = Rect(
            (min(x1, x2) * frameReader.width).roundToInt(),
            (min(y1, y2) * frameReader.height).roundToInt(),
            (max(x1, x2) * frameReader.width).roundToInt(),
            (max(y1, y2) * frameReader.height).roundToInt()
        )
        val xy = IntArray(2)
        bubble.getLocationOnScreen(xy)
        val margin = (12 * resources.displayMetrics.density).roundToInt()
        val button = Rect(xy[0] - margin, xy[1] - margin,
            xy[0] + bubble.width + margin, xy[1] + bubble.height + margin)
        val protected=listOf(panel, Rect((.18*frameReader.width).toInt(),(.02*frameReader.height).toInt(),(.82*frameReader.width).toInt(),(.22*frameReader.height).toInt()),
            Rect((.02*frameReader.width).toInt(),(.89*frameReader.height).toInt(),(.98*frameReader.width).toInt(),frameReader.height),
            Rect((.30*frameReader.width).toInt(),(.50*frameReader.height).toInt(),(.70*frameReader.width).toInt(),(.62*frameReader.height).toInt()),
            Rect((.80*frameReader.width).toInt(),(.40*frameReader.height).toInt(),(.94*frameReader.width).toInt(),(.47*frameReader.height).toInt()),
            Rect((.04*frameReader.width).toInt(),(.72*frameReader.height).toInt(),(.47*frameReader.width).toInt(),(.83*frameReader.height).toInt()),
            Rect((.14*frameReader.width).toInt(),(.12*frameReader.height).toInt(),(.82*frameReader.width).toInt(),(.35*frameReader.height).toInt()))
        return protected.any { Rect.intersects(it,button) }
    }

    private fun showScanResult(outcome: OverlayIvProcessor.Outcome, elapsedMs: Long) {
        overlay?.let { button ->
            val status=when(outcome.phase) {
                OverlayIvProcessor.Phase.SNAPSHOT -> "画像確保・解析中"
                OverlayIvProcessor.Phase.CP_READY -> if(outcome.displayCp==null) "CPを追加確認中" else "CP認識・解析中"
                OverlayIvProcessor.Phase.SAVED -> "保存済み"
                OverlayIvProcessor.Phase.REVIEW -> "要確認・再読取"
                OverlayIvProcessor.Phase.FAILED -> outcome.text.take(22)
            }
            button.text="CP ${outcome.displayCp ?: "—"}\nIV ${outcome.displayIv ?: "—"}\n$status"
            button.textSize=11f
            button.contentDescription=button.text.toString()+"。"+outcome.text
        }
        refreshReviewBadge()
        if(outcome.phase!=OverlayIvProcessor.Phase.SNAPSHOT && outcome.phase!=OverlayIvProcessor.Phase.CP_READY)
            updateNotification("${outcome.text} (${elapsedMs}ms)")
    }

    /** Resolve CP and/or form sequentially against the same immutable record. */
    private fun reviewInAppraisal(outcome: OverlayIvProcessor.Outcome) {
        var generation = reviewGeneration
        var saving = false
        fun complete() {
            OverlayIvProcessor.worker.execute {
                val row=AutoIvRecords.load(this).firstOrNull { it.recordId==outcome.recordId }
                main.post {
                    if(hasStopped || !overlayVisible || generation!=reviewGeneration) return@post
                    appraisalReview.dismiss()
                    if(row!=null) showScanResult(OverlayIvProcessor.Outcome(
                        "${row.source} CP${row.cp} / ${row.targets.joinToString("、")}", row.ready,
                        row.iv, row.cp.takeIf { it>0 }, if(row.ready && row.shadow != ShadowRecognizer.State.UNKNOWN && row.genderPending.isEmpty()) OverlayIvProcessor.Phase.SAVED else OverlayIvProcessor.Phase.REVIEW),0L)
                }
            }
        }
        fun chooseForm() {
            if(outcome.formCandidates.isEmpty()) { complete(); return }
            appraisalReview.show("姿を選んでください", "IV ${outcome.displayIv.orEmpty()}・評価画面の個体を確認してください",
                outcome.formCandidates.map { (id,name) -> name to {
                    if(!saving) {
                    saving = true
                    OverlayIvProcessor.worker.execute {
                        val row=AutoIvRecords.load(this).firstOrNull { it.recordId==outcome.recordId }
                        if(row!=null) AutoIvRecords.selectForm(this,row,id)
                        val saved=AutoIvRecords.load(this).firstOrNull { it.recordId==outcome.recordId }?.manualFormId==id
                        main.post {
                            if(!hasStopped && appraisalReview.isShowing && generation==reviewGeneration) {
                                saving = false
                                if(saved) complete() else appraisalReview.showError("姿を保存できませんでした。再選択するか、今は決めないで閉じてください")
                            }
                        }
                    }
                    }
                } })
            generation = reviewGeneration
        }
        if(outcome.displayCp!=null) { chooseForm(); return }
        fun chooseCp(cp:Int) {
            if(saving) return
            saving = true
            OverlayIvProcessor.worker.execute {
                val saved=AutoIvRecords.selectCp(this,outcome.recordId,cp)
                main.post {
                    if(!hasStopped && appraisalReview.isShowing && generation==reviewGeneration) {
                        saving = false
                        if(saved) chooseForm() else appraisalReview.showError("CPを保存できませんでした。再選択するか、今は決めないで閉じてください")
                    }
                }
            }
        }
        appraisalReview.show("CPを確認してください",
            "IV ${outcome.displayIv.orEmpty()}。複数フレームを比較しました。読めない場合は、今は決めない→光が動いてから再読取できます。",
            outcome.cpCandidates.map { cp -> "CP $cp" to { chooseCp(cp) } }, ::chooseCp)
        generation = reviewGeneration
    }

    private fun refreshReviewBadge() {
        overlay?.let { button ->
            val base=button.text.toString().substringBefore("\n要確認発生")
            button.text=base+if(reviewCount>0) "\n要確認発生 ${reviewCount}件" else ""
        }
    }

    private fun showScanResult(message: String, added: Boolean) {
        showScanResult(OverlayIvProcessor.Outcome(message, added),
            SystemClock.elapsedRealtime() - scanStartedAt)
    }

    private fun removeOverlay() {
        ReaderOverlayVisibility.register(null)
        learningRequestGeneration++
        learningPopup?.dismiss(); learningPopup = null
        learningDialog?.dismiss(); learningDialog = null
        cancelLiveThrow()
        throwDialog?.dismiss()
        clearRecordedThrow()
        GoTextInputAccessibilityService.stopCatchTrial()
        autoScan?.cancel()
        overlayLayout?.let { readerPosition = it.x to it.y }
        readerQuickMenu?.destroy()
        readerQuickMenu = null
        overlayVisible=false
        appraisalReview.dismiss()
        cpFrames.clear()
        captureGate.clear()
        shadowBurst.reset()
        previousScene=null
        lastObservedAt=0L
        shadowOverlayRoot?.let { view ->
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
            } catch (_: Exception) {}
        }
        shadowOverlayRoot = null
        overlay = null
        overlayLayout = null
    }

    private fun saveCapture(image: Image) {
        val plane = image.planes.firstOrNull() ?: return
        if (plane.pixelStride != 4 || plane.rowStride % 4 != 0) {
            throw IllegalStateException("Unsupported image stride")
        }
        val width = image.width
        val height = image.height
        val rowSize = width * 4
        require(plane.rowStride >= rowSize) { "Invalid row stride" }
        // ImageReader buffers may omit padding on the LAST row. Copy only real
        // pixels row-by-row to avoid BufferUnderflowException on some devices.
        val packed = ByteBuffer.allocateDirect(rowSize * height)
        val source = plane.buffer
        val line = ByteArray(rowSize)
        for (y in 0 until height) {
            source.position(y * plane.rowStride)
            source.get(line)
            packed.put(line)
        }
        packed.flip()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.copyPixelsFromBuffer(packed)
            val dir = File(filesDir, CAPTURE_DIR).apply { mkdirs() }
            val name = "capture_${System.currentTimeMillis()}_${SystemClock.uptimeMillis()}.png"
            val destination = File(dir, name)
            try {
                FileOutputStream(destination).use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw IllegalStateException("PNG encode failed")
                    }
                }
            } catch (e: Exception) {
                destination.delete()
                throw e
            }
            trimCaptureFiles(dir)
            captureCount++
            main.post { updateNotification("${captureCount}枚撮影済み。アプリへ戻って読み込めます") }
        } finally {
            bitmap.recycle()
        }
    }

    private fun trimCaptureFiles(dir: File) {
        dir.listFiles { file -> file.isFile && file.name.endsWith(".png") }
            ?.sortedBy { it.name }
            ?.dropLast(MAX_CAPTURES)
            ?.forEach { it.delete() }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "個体値画面の撮影", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, ScreenCaptureService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(
            this, 101, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("PokemonIVReader：画面取得中")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_camera, "2秒後に撮影", serviceAction(ACTION_CAPTURE, 102))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "共有を終了", serviceAction(ACTION_STOP, 103))
            .build()
    }

    private fun updateNotification(message: String) {
        if (!hasStopped) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, notification(message))
        }
    }

    private fun closeProjection(stopProjection: Boolean) {
        if (hasStopped) return
        hasStopped = true
        isSessionActive=false
        teachingFrame.getAndSet(null)?.result?.invoke(null)
        pipeline.clear()
        activeCaptureId=0L
        captureGate.clear()
        capturePending.set(false)
        shadowBurst.reset()
        main.removeCallbacks(scanTimeout)
        scanInProgress.set(false)
        captureByOverlay = false
        scheduled = false
        removeOverlay()

        // Take ownership of the old resources before an async callback can
        // arrive. The callback is delivered on captureThread, not the UI thread.
        val oldReader = reader
        reader = null
        val oldDisplay = virtualDisplay
        virtualDisplay = null
        val oldThread = captureThread
        captureThread = null
        val oldProjection = projection
        projection = null

        // Stop scheduling new frame callbacks and stop the image producer.
        // Do NOT close ImageReader here while its worker callback may still
        // hold an Image or access a native buffer.
        try { oldReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { oldDisplay?.release() } catch (_: Exception) {}

        if (oldThread != null) {
            val posted = try {
                Handler(oldThread.looper).post {
                    // Runs on the same thread as OnImageAvailableListener.
                    // All previously queued frame callbacks can finish first.
                    try { oldReader?.close() } catch (_: Exception) {}
                    oldThread.quitSafely()
                }
            } catch (_: Exception) {
                false
            }
            if (!posted) {
                try { oldReader?.close() } catch (_: Exception) {}
                oldThread.quitSafely()
            }
        } else {
            try { oldReader?.close() } catch (_: Exception) {}
        }

        if (oldProjection != null) {
            if (stopProjection) {
                try { oldProjection.stop() } catch (_: Exception) {}
            }
            try { oldProjection.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        com.example.pokemonivreader.overlayqueue.SearchOverlayVisibility.remove(searchVisibilityListener)
        readerOverlayRequested = false
        closeProjection(stopProjection = true)
        super.onDestroy()
    }
}




