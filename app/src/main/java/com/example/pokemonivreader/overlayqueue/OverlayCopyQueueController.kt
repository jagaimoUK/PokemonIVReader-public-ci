package com.example.pokemonivreader.overlayqueue

import android.app.AlertDialog
import android.content.Context
import android.text.TextUtils
import com.example.pokemonivreader.PokemonCatalog
import com.example.pokemonivreader.readerquickmenu.ReaderCleanupStore
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Only the bounded panel receives touches. Outside taps pass through to GO. */
class OverlayCopyQueueController private constructor(private val app: Context) {
    companion object {
        @Volatile private var instance: OverlayCopyQueueController? = null
        private val hiddenLeases = mutableMapOf<View, Int>()
        fun get(context: Context): OverlayCopyQueueController = instance
            ?: synchronized(this) { instance ?: OverlayCopyQueueController(context.applicationContext).also { instance = it } }
        internal fun coveredRects(): List<Rect> = instance?.let { controller ->
            listOfNotNull(controller.panel, controller.bubble).mapNotNull { view ->
                if (view.visibility != View.VISIBLE || !view.isAttachedToWindow) null else {
                    val xy = IntArray(2); view.getLocationOnScreen(xy)
                    Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
                }
            }
        } ?: emptyList()
        internal fun isCovered(bounds: Rect): Boolean = coveredRects().any { Rect.intersects(bounds, it) }

        /** Hide only our windows, not GO, for screen capture or a programmatic gesture. */
        internal fun pauseVisualOverlay(persistent: Boolean = false): () -> Unit {
            val controller = instance ?: return {}
            val views = listOfNotNull(controller.panel, controller.bubble, controller.dropTarget)
                .filter { it.isAttachedToWindow && (it.visibility == View.VISIBLE || hiddenLeases.containsKey(it)) }
            views.forEach { hiddenLeases[it] = (hiddenLeases[it] ?: 0) + 1; it.visibility = View.INVISIBLE }
            var restored = false
            val restore = {
                if (!restored) {
                    restored = true
                    views.forEach { view ->
                        val count = (hiddenLeases[view] ?: 1) - 1
                        if (count > 0) hiddenLeases[view] = count else {
                            hiddenLeases.remove(view)
                            val current = instance
                            val wanted = current?.panel === view ||
                                (current?.bubble === view && current.panel == null) || current?.dropTarget === view
                            if (wanted && view.isAttachedToWindow && view.visibility == View.INVISIBLE) view.visibility = View.VISIBLE
                        }
                    }
                }
            }
            // A destroyed accessibility service must never leave the panel invisible.
            val handler = Handler(Looper.getMainLooper())
            val watchdog = Runnable { restore() }
            if (!persistent) handler.postDelayed(watchdog, 2_000L)
            return {
                handler.removeCallbacks(watchdog)
                restore()
            }
        }

        internal fun showInputHint(message: String) { instance?.setInputStatus(message) }
        internal fun showNameOkRecovery(message: String, logExcerpt: String, keyboardBlocked: Boolean = false) {
            instance?.openNameOkRecovery(message, logExcerpt, keyboardBlocked)
        }
        internal fun showLearningResult(message: String) {
            instance?.apply {
                learningResult = message
                renderInputStatus(message)
            }
        }
        internal fun showWorkflowHint(message: String, confirm: (() -> Unit)?) =
            showWorkflowHint(message, "種・姿・IVを照合して続行", confirm)
        internal fun showWorkflowHint(message: String, label: String, confirm: (() -> Unit)?) {
            instance?.apply {
                setInputStatus(message)
                confirmAction = confirm
                confirmButton?.text = label
                confirmButton?.visibility = if (confirm == null) View.GONE else View.VISIBLE
            }
        }
    }
    private val store = OverlayQueueStore(app)
    private val readerCleanupStore = ReaderCleanupStore(app)
    private var readerCleanupMode = false
    private var readerCleanupItems: List<ReaderCleanupStore.SearchItem> = emptyList()
    private val placement = app.getSharedPreferences("overlay_queue_placement", Context.MODE_PRIVATE)
    private val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bubble: TextView? = null
    private var dropTarget: TextView? = null
    private var dropParams: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var list: ListView? = null
    private var adapter: QueueAdapter? = null
    private var rows: List<OverlayQueueStore.Item> = emptyList()
    private var inputPending = false
    private var inputStatus: TextView? = null
    private var recoveryDialog: AlertDialog? = null
    private var learningResult: String? = null
    private var confirmAction: (() -> Unit)? = null
    private var confirmButton: Button? = null
    private var stopButton: Button? = null
    private var handoffModeButton: Button? = null
    private var prevPreparationButton: Button? = null
    // Preference is independent from pending work: ON never resumes a job by itself.
    private var manualHandoffs = placement.getBoolean("manual_handoffs_v1", false)
    private var skipUnreadableCp = placement.getBoolean("skip_unreadable_search_cp", false)
    private var showNavigation = false
    private var continuous = placement.getBoolean("continuous_enabled_v1", true)
    private var sequenceRunning = false
    private var prevSequenceAuthorized = false // Only an explicit prev button may authorize reset continuation.
    private val controlsLocked get() = inputPending
    private var continuousGeneration = 0L
    private fun stopContinuous() {
        sequenceRunning = false
        prevSequenceAuthorized = false
        continuousGeneration++ // Invalidate any already-posted next request.
    }
    /** Process only the first result for this specific request, on the UI thread. */
    private fun onceWhileCurrent(
        current: () -> Boolean,
        complete: (Boolean, String) -> Unit,
    ): (Boolean, String) -> Unit {
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
        return result@{ ok, message ->
            if (!delivered.compareAndSet(false, true)) return@result
            val handle = Runnable { if (current()) complete(ok, message) }
            if (Looper.myLooper() == Looper.getMainLooper()) handle.run()
            else Handler(Looper.getMainLooper()).post(handle)
        }
    }

    private var retryAction: (() -> Unit)? = null
    private var hasSessionItems = false
    private val defaultInputRequester: (GoTextInputAccessibilityService.Target, String, (Boolean, String) -> Unit) -> Boolean =
        GoTextInputAccessibilityService::request
    internal var inputRequester: (GoTextInputAccessibilityService.Target, String, (Boolean, String) -> Unit) -> Boolean =
        defaultInputRequester
    internal var goLocaleProvider: () -> java.util.Locale? = GoTextInputAccessibilityService::detectedGoLocale
    internal var keepTagReserver: (Long, Long) -> Boolean = store::reserveKeepTag
    internal var individualRequester: (OverlayQueueStore.Item, GoTextInputAccessibilityService.Target, (Boolean, String) -> Unit) -> Boolean =
        { item, target, result ->
            val session = store.currentSessionId
            GoTextInputAccessibilityService.requestIndividual(item, target, continuingSequence = sequenceRunning,
                finishAfterName = manualHandoffs, skipUnreadableCp = skipUnreadableCp,
                reserveKeepTag = { keepTagReserver(item.id, session) }, result = result)
        }
    private val defaultPrevResetRequester: (String, (Boolean, String) -> Unit) -> Boolean =
        GoTextInputAccessibilityService::requestPrevReset
    internal var prevResetRequester: (String, (Boolean, String) -> Unit) -> Boolean =
        defaultPrevResetRequester
    // Injectable solely to test Android's launcher resolution and already-open GO.
    internal var goForegroundForPrev: () -> Boolean = GoTextInputAccessibilityService::isGameForeground
    internal var goLauncherForPrev: () -> Boolean = { GoGameLaunch.open(app) }
    internal var cleanupRequester: (GoSendWorkflow.Batch, (String) -> Boolean, (Boolean, String) -> Unit) -> Boolean =
        { batch, current, result -> GoTextInputAccessibilityService.requestSend(batch, current, result) }
    internal var automationEntryRequester: (Context, () -> Boolean, (Boolean, String) -> Unit) -> Boolean =
        GoTextInputAccessibilityService::requestAutomationEntry
    internal var sendLandingRequester: ((Boolean, String) -> Unit) -> Boolean =
        GoTextInputAccessibilityService::requestSendLanding

    private val itemsById = mutableMapOf<Long, OverlayQueueStore.Item>()
    private val itemPositions = mutableMapOf<Long, Int>()
    private var steps: List<OverlayQueuePresentation.Step> = emptyList()
    private var visibleSteps: List<OverlayQueuePresentation.Step> = emptyList()
    private var cursor = OverlayQueueCursor()
    private var readerCursor = OverlayQueueCursor()
    private var flowSession = Long.MIN_VALUE
    private var migrateCleanupCursor = false
    private var recoverLegacyCleanupRows = false
    private var migratePrevGroupCursor = false
    private var flow = OverlayQueueFlow()
    private var previousButton: Button? = null
    private var nextButton: Button? = null
    private fun flowKey(suffix: String) = "flow_${store.currentSessionId}_$suffix"
    private fun loadFlow() {
        if (flowSession == store.currentSessionId) return
        stopContinuous()
        flowSession = store.currentSessionId
        migratePrevGroupCursor = !placement.contains(flowKey("cursor_v7")) &&
            (placement.contains(flowKey("cursor_v6")) || placement.contains(flowKey("cursor_v5")) ||
                placement.contains(flowKey("cursor_v4")) || placement.contains(flowKey("cursor_v3")))
        recoverLegacyCleanupRows = !placement.contains(flowKey("cursor_v7")) &&
            !placement.contains(flowKey("cursor_v6"))
        migrateCleanupCursor = recoverLegacyCleanupRows && !placement.contains(flowKey("cursor_v5"))
        val position = if (placement.contains(flowKey("cursor_v7"))) placement.getInt(flowKey("cursor_v7"), 0)
        else if (!recoverLegacyCleanupRows) placement.getInt(flowKey("cursor_v6"), 0)
        else if (!migrateCleanupCursor) placement.getInt(flowKey("cursor_v5"), 0)
        else if (placement.contains(flowKey("cursor_v4"))) placement.getInt(flowKey("cursor_v4"), 0)
        else {
            val previous = placement.getInt(flowKey("cursor_v3"), 0)
            val oldPrefix = if (previous > 0) OverlayCleanupSearch.prevResetQueries(
                store.items(filter = OverlayQueueStore.Filter.ALL).map { it.search }).size else 0
            previous - oldPrefix
        }
        cursor = OverlayQueueCursor(position)
        flow = OverlayQueueFlow()
    }
    private fun saveFlow() {
        placement.edit().putInt(flowKey("cursor_v7"), cursor.position).apply()
    }
    private fun previousIndividual() {
        if (controlsLocked) return
        stopContinuous()
        if (readerCleanupMode) readerCursor.back() else { cursor.back(); saveFlow() }
        refresh(reload = false); list?.setSelectionFromTop(0, 0)
    }
    private fun nextIndividual() {
        if (inputPending) return
        if (sequenceRunning) return
        stopContinuous()
        if (readerCleanupMode) readerCursor.next(readerCleanupItems.size)
        else { cursor.next(steps.size); saveFlow() }
        refresh(reload = false); list?.setSelectionFromTop(0, 0)
    }

    // Retain the last visible item and exact pixel offset when the panel is collapsed.
    // A different import session starts at the top rather than an unrelated old row.
    private fun saveListScroll() {
        val currentList = list ?: return
        placement.edit()
            .putLong("scroll_session_id", store.currentSessionId)
            .putInt("scroll_row", currentList.firstVisiblePosition)
            .putInt("scroll_offset", currentList.getChildAt(0)?.top ?: 0)
            .apply()
    }

    private fun restoreListScroll() {
        val currentList = list ?: return
        if (placement.getLong("scroll_session_id", -1L) != store.currentSessionId) return
        val position = placement.getInt("scroll_row", 0)
        val offset = placement.getInt("scroll_offset", 0)
        val session = store.currentSessionId
        currentList.post {
            if (list !== currentList || store.currentSessionId != session) return@post
            val count = currentList.adapter?.count ?: 0
            if (count > 0) currentList.setSelectionFromTop(position.coerceIn(0, count - 1), offset)
        }
    }
    private fun dp(n: Int) = (n * app.resources.displayMetrics.density + .5f).toInt()
    private fun toast(s: String) = Toast.makeText(app, s, Toast.LENGTH_SHORT).show()
    private fun setInputStatus(message: String?) {
        learningResult = null
        renderInputStatus(message)
    }
    private fun renderInputStatus(message: String?) {
        inputStatus?.apply {
            val active = !message.isNullOrBlank()
            text = if (active) message else "状態：待機中（操作の結果・停止理由はここに表示されます）"
            visibility = View.VISIBLE
            setTextColor(if (active) Color.rgb(225, 244, 232) else Color.rgb(160, 178, 168))
        }
    }
    private fun background(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(24).toFloat() }
    private fun label(s: String, size: Float = 12f) = TextView(app).apply {
        text = s; textSize = size; setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(3), dp(2), dp(3), dp(2))
    }
    private fun button(s: String, action: () -> Unit) = Button(app).apply {
        text = s; textSize = 12f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(dp(3), 0, dp(3), 0); setTextColor(Color.WHITE)
        background = background(Color.rgb(79, 96, 88))
        setOnClickListener { action() }
    }
    private fun params(width: Int, height: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        width, height,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y
        // Android 12+ allows touches through an untrusted overlay at <=0.8 opacity.
        // Only one of bubble/panel is visible, so their opacity never accumulates.
        alpha = .8f
    }
    private fun showDropTarget() {
        if (dropTarget != null) return
        val m = app.resources.displayMetrics
        val size = dp(66)
        val p = params(size, size, ((m.widthPixels - size) / 2).coerceAtLeast(0),
            (m.heightPixels - size - dp(64)).coerceAtLeast(0)).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val view = label("×", 30f).apply {
            gravity = Gravity.CENTER
            contentDescription = "ここへ厳選アイコンを移動すると終了"
            background = background(Color.rgb(117, 38, 51))
        }
        try { manager.addView(view, p); dropTarget = view; dropParams = p }
        catch (_: Exception) { dropTarget = null; dropParams = null }
    }
    private fun isOverDropTarget(b: WindowManager.LayoutParams): Boolean {
        val target = dropParams ?: return false
        val cx = b.x + b.width / 2
        val cy = b.y + b.height / 2
        return cx >= target.x && cx <= target.x + target.width &&
            cy >= target.y && cy <= target.y + target.height
    }
    private fun updateDropTarget(p: WindowManager.LayoutParams) {
        val active = isOverDropTarget(p)
        dropTarget?.background = background(if (active) Color.rgb(188, 51, 67) else Color.rgb(117, 38, 51))
        dropTarget?.text = if (active) "✓" else "×"
    }
    private fun hideDropTarget() {
        dropTarget?.let { try { manager.removeView(it) } catch (_: Exception) {} }
        dropTarget = null; dropParams = null
    }
    fun show(): Boolean {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(app)) return false
        if (bubble != null) return true
        val metrics = app.resources.displayMetrics
        val maxX = (metrics.widthPixels - dp(48)).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - dp(48)).coerceAtLeast(0)
        val x = (placement.getFloat("bubble_x_ratio", 1f).coerceIn(0f, 1f) * maxX).toInt()
        val ratioY = placement.getFloat("bubble_y_ratio", -1f)
        val y = if (ratioY < 0f) dp(150).coerceAtMost(maxY) else (ratioY.coerceIn(0f, 1f) * maxY).toInt()
        val v = label("厳選", 12f).apply {
            gravity = Gravity.CENTER; background = background(Color.rgb(30, 80, 120))
            contentDescription = "厳選リストを開く。長押しでオーバーレイ終了"
        }
        val p = params(dp(48), dp(48), x, y)
        try { manager.addView(v, p) } catch (e: Exception) { toast("表示できません: ${e.localizedMessage}"); return false }
        bubble = v
        v.setOnClickListener { togglePanel() }
        v.setOnLongClickListener { hide(); true }
        v.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false
            val longPress = Runnable { if (!dragged) { v.isPressed = false; v.performLongClick() } }
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY; startX = p.x; startY = p.y; dragged = false
                        v.isPressed = true; v.postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX; val dy = event.rawY - downY
                        if (abs(dx) > dp(8) || abs(dy) > dp(8)) { dragged = true; v.removeCallbacks(longPress) }
                        if (dragged) {
                            showDropTarget()
                            p.x = (startX + dx.toInt()).coerceIn(0, maxX); p.y = (startY + dy.toInt()).coerceIn(0, maxY)
                            try { manager.updateViewLayout(v, p) } catch (_: Exception) {}
                            updateDropTarget(p)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.removeCallbacks(longPress)
                        val remove = dragged && isOverDropTarget(p)
                        hideDropTarget()
                        if (remove) { v.isPressed = false; hide(); return true }
                        if (dragged) placement.edit()
                            .putFloat("bubble_x_ratio", if (maxX == 0) 0f else p.x.toFloat() / maxX)
                            .putFloat("bubble_y_ratio", if (maxY == 0) 0f else p.y.toFloat() / maxY).apply()
                        else if (v.isPressed && bubble != null) v.performClick()
                        v.isPressed = false; return true
                    }
                    MotionEvent.ACTION_CANCEL -> { hideDropTarget(); v.removeCallbacks(longPress); v.isPressed = false; return true }
                }
                return false
            }
        })
        return true
    }
    /**
     * Reuse the existing GO search overlay; keep these searches separate from
     * the SQLite nickname/IV session so no history or check marks are lost.
     */
    fun showReaderCleanup(): Boolean {
        if (controlsLocked) return false
        val latest = readerCleanupStore.items()
        if (latest.isEmpty()) {
            toast("非厳選検索式が保存されていません")
            return false
        }
        stopContinuous()
        readerCleanupItems = latest
        readerCursor = OverlayQueueCursor()
        if (panel != null && !readerCleanupMode) saveListScroll()
        readerCleanupMode = true
        if (panel == null) openPanel() else refresh()
        list?.setSelectionFromTop(0, 0)
        return panel != null
    }

    private fun tagPlanSafe(): Boolean {
        if (store.hasTagPlanV2()) return true
        stopContinuous()
        setInputStatus("旧セッションの保護タグ用途を復元できません。誤解除を防ぐため停止しました。厳選結果を新たに取り込んでください")
        return false
    }

    /** Old GO prev labels do not encode whether a Mankey is for Primeape or Annihilape.
     * Only an explicit manual migration acknowledgement permits a first destructive reset. */
    private fun independentTagsReady(items: List<OverlayQueueStore.Item>): Boolean {
        if (items.none { SelectionTagPolicy.isIndependentTarget(it.targetName) ||
                SelectionTagPolicy.ambiguousMankeySource(it.search, it.targetName) }) return true
        if (placement.getBoolean("mankey_subprev_migrated_v1", false)) return true
        stopContinuous()
        setInputStatus("マンキー系統の旧prevは進化先を判別できません。GOで既存タグを確認し、subprevを作成・移行してから「その他→整理→独立タグ移行を確認」を選んでください")
        return false
    }

    private fun prevPlanKey(search: String, targetName: String?): String? {
        val tag = SelectionTagPolicy.tagForTarget(targetName)
        val base = OverlayCleanupSearch.prevReset(search, tag) ?: return null
        val token = base.substringBefore('&')
        val catalog = com.example.pokemonivreader.PokemonCatalog.get(app)
        val familyKey = catalog.goSearchEvolutionFamilyKey(token) ?: return base
        return "$familyKey&${base.substringAfter('&')}"
    }

    private fun prevPlanKey(item: OverlayQueueStore.Item) = prevPlanKey(item.search, item.targetName)
    private fun taggedSteps(items: List<OverlayQueueStore.Item>) =
        OverlayQueuePresentation.build(items, ::prevPlanKey)

    private fun verifiedPrevQueries(
        searches: List<String>, tagName: String = SelectionTagPolicy.PREV,
        goLocale: java.util.Locale? = goLocaleProvider(),
    ): List<String>? = runCatching {
        val catalog = com.example.pokemonivreader.PokemonCatalog.get(app)
        val verified = OverlayCleanupSearch.verifiedPrevResetQueries(
            searches, catalog::isKnownGoSearchSpecies, tagName) ?: return@runCatching null
        verified.map { query ->
            val parts = query.split('&')
            val token = parts.first()
            if (!catalog.hasSafeRegionalResetScope(token, parts.drop(1))) return@runCatching null
            // A numeric evolution root without a confirmed GO language cannot
            // safely reset an entire family using only its numeric species query.
            if (catalog.goSearchEvolutionFamilyKey(token) != null && goLocale == null &&
                token.toIntOrNull() != null) return@runCatching null
            val name = catalog.goSearchEvolutionFamilyToken(token, goLocale) ?:
                catalog.goSearchSpeciesName(token, goLocale)
            when {
                name != null -> {
                    val exclude = if (name.startsWith('+')) "" else
                        catalog.goSearchPrefixExclusions(name, goLocale).joinToString("") { "&!$it" }
                    "$name&${query.substringAfter('&')}$exclude"
                }
                goLocale == null && token.toIntOrNull() != null &&
                    prevResetRequester === defaultPrevResetRequester -> query
                else -> return@runCatching null
            }
        }.distinctBy { it.lowercase(java.util.Locale.ROOT) }
    }.getOrNull()

    private fun verifiedPrevCount(searches: List<String>, tagName: String): Int? =
        verifiedPrevQueries(searches, tagName)?.size

    private fun independentCandidatesSafe(items: List<OverlayQueueStore.Item>): Boolean {
        val catalog = com.example.pokemonivreader.PokemonCatalog.get(app)
        val conflict = items.filter { it.nickname.isNotBlank() }.groupBy { item ->
            val parts = OverlayGoSearch.normalize(item.search).split('&')
            val species = parts.first().trim()
            val identity = catalog.goSearchDexNumber(species)?.toString() ?: GoWorkflowPolicy.normalized(species)
            val filters = parts.drop(1).map(GoWorkflowPolicy::normalized).distinct().sorted()
            identity to filters
        }.values.any { group ->
            group.map { SelectionTagPolicy.tagForTarget(it.targetName) to it.nickname }.distinct().size > 1
        }
        if (!conflict) return true
        stopContinuous()
        setInputStatus("同じ検索条件に異なる名前・タグの指定があり、実個体を識別できません。自動命名・タグ操作を停止しました")
        return false
    }

    private fun groupAlreadyTagged(
        step: OverlayQueuePresentation.Step,
        items: Collection<OverlayQueueStore.Item>,
        olderPending: List<OverlayQueueStore.Item?>,
    ): Boolean {
        val catalog = com.example.pokemonivreader.PokemonCatalog.get(app)
        fun scope(search: String): Pair<String, Boolean?>? {
            val base = OverlayCleanupSearch.base(search)?.split('&') ?: return null
            val species = base.first()
            val identity = catalog.goSearchEvolutionFamilyKey(species)
                ?: catalog.goSearchDexNumber(species)?.toString() ?: return null
            val shadow = base.drop(1).firstOrNull { it.equals("shadow", true) || it.equals("!shadow", true) }
            return identity to shadow?.equals("shadow", true)
        }
        val scopes = step.prevSources.mapNotNull(::scope).map { (identity, shadow) ->
            identity to (shadow ?: false)
        }.toSet()
        val tag = SelectionTagPolicy.tagForTarget(step.target)
        fun overlaps(item: OverlayQueueStore.Item?): Boolean {
            if (item == null) return true // The reservation survived but its scope cannot be recovered.
            if (SelectionTagPolicy.tagForTarget(item.targetName) != tag &&
                !SelectionTagPolicy.ambiguousMankeySource(item.search, item.targetName)) return false
            val (identity, shadow) = scope(item.search) ?: return true
            return scopes.any { (otherIdentity, otherShadow) ->
                identity == otherIdentity && (shadow == null || shadow == otherShadow)
            }
        }
        return olderPending.any(::overlaps) || items.any { item ->
            (store.keepTagReserved(item.id) || store.keepTagVerified(item.id) || store.workflowVerified(item.id, false)) &&
                overlaps(item)
        }
    }

    private fun olderPendingKeepTags(session: Long) =
        store.pendingKeepTagItems().filter { (owner, _) -> owner != session }.map { (_, item) -> item }

    private fun showPendingKeepTags() {
        if (controlsLocked) return
        val pending = store.pendingKeepTagItems().sortedByDescending { it.first }
        if (pending.isEmpty()) {
            setInputStatus("確認待ちの保護予約はありません")
            return
        }
        val labels = pending.map { (session, item) ->
            "履歴 #$session · ${item?.speciesLabel ?: "対象データを確認できません"}"
        }.toTypedArray()
        val dialog = AlertDialog.Builder(app)
            .setTitle("確認待ちの保護予約")
            .setItems(labels) { _, which ->
                if (controlsLocked) return@setItems
                val (session, item) = pending[which]
                if (item == null) {
                    setInputStatus("履歴と保護予約が一致しません。該当範囲の解除を見送ります")
                } else {
                    store.currentSessionId = session
                    onImported()
                    itemPositions[item.id]?.let { index ->
                        cursor.moveTo(index, steps.size)
                        saveFlow()
                        refresh(reload = false)
                        list?.setSelectionFromTop(0, 0)
                    }
                    setInputStatus("履歴を開きました。GOで個体を確認し、自動モードで保護タグの確認まで実行すると予約が解消されます")
                }
            }
            .setNegativeButton("閉じる", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    private fun prevGroupCheckpoint(session: Long, index: Int) =
        "session_${session}_group_${index}_index_v2"
    private fun prevGroupDone(index: Int, step: OverlayQueuePresentation.Step): Boolean =
        step.prevSources.isNotEmpty() &&
            placement.getInt(prevGroupCheckpoint(store.currentSessionId, index), -1) ==
                verifiedPrevCount(step.prevSources, SelectionTagPolicy.tagForTarget(step.target))

    /** A group Prev reset always precedes its own first IV search. */
    internal fun startPrevGroup(index: Int, directlyStarted: Boolean = true) {
        if (controlsLocked || readerCleanupMode) return
        val step = steps.getOrNull(index)?.takeIf { it.prevSources.isNotEmpty() } ?: return
        val session = store.currentSessionId
        val items = store.items(id = session, filter = OverlayQueueStore.Filter.ALL)
        if (session < 0 || !tagPlanSafe() || !independentCandidatesSafe(items)) return
        if (SelectionTagPolicy.isIndependentTarget(step.target) &&
            !independentTagsReady(items.filter { SelectionTagPolicy.isIndependentTarget(it.targetName) })) return
        if (step.prevSources.any { SelectionTagPolicy.ambiguousMankeySource(it, step.target) }) {
            setInputStatus("マンキー系統の進化先を特定できません。タグ解除を停止しました")
            return
        }
        if (prevGroupDone(index, step)) {
            setInputStatus("この系統の${SelectionTagPolicy.tagForTarget(step.target)}解除は完了済みです")
            return
        }
        val tagName = SelectionTagPolicy.tagForTarget(step.target)
        val queries = verifiedPrevQueries(step.prevSources, tagName)
            ?: run { setInputStatus("種名を安全に確認できないためprev解除を開始しません"); return }
        // A successful nickname/tag operation for this evolution target must
        // never be followed by a reset of the same target's current tags.
        if (groupAlreadyTagged(step, items, olderPendingKeepTags(session))) {
            setInputStatus("この進化先の今回タグ付けが始まっています。prev解除でタグを戻さないよう停止しました")
            return
        }
        if (directlyStarted) {
            stopContinuous()
            cursor.moveTo(index, steps.size)
            saveFlow()
            if (continuous && !manualHandoffs) {
                sequenceRunning = true
                prevSequenceAuthorized = true
            }
        }
        if (panel == null) openPanel()
        if (panel == null) { toast("オーバーレイの表示を許可してください"); return }
        val generation = continuousGeneration
        val key = prevGroupCheckpoint(session, index)
        fun current() = generation == continuousGeneration &&
            store.currentSessionId == session && panel != null
        fun stop(message: String) {
            if (!current()) return
            inputPending = false
            stopContinuous()
            setInputStatus(message)
            refreshInputControls()
        }
        fun finishGroup() {
            if (!current()) return
            inputPending = false
            retryAction = null
            cursor.moveTo(index + 1, steps.size)
            saveFlow()
            refresh(reload = false)
            if (sequenceRunning) continueWithNextStep(session)
            else setInputStatus("「${step.target}」のprev解除を確認しました")
        }
        fun next() {
            if (!current()) return
            val at = placement.getInt(key, 0)
            if (at !in 0..queries.size) {
                stop("prev解除の保存済み進捗が一致しません"); return
            }
            if (at == queries.size) { finishGroup(); return }
            setInputStatus("「${step.target}」prev解除 ${at + 1}/${queries.size}")
            val complete = onceWhileCurrent(::current) { ok, message ->
                if (!ok) stop(message)
                else if (!placement.edit().putInt(key, at + 1).commit())
                    stop("prev解除の進捗を保存できませんでした")
                else panel?.post { next() }
            }
            if (!prevResetRequester(queries[at], complete))
                complete(false, "ユーザー補助を有効にしてprev解除を再開してください")
        }
        retryAction = { startPrevGroup(index, directlyStarted = true) }
        inputPending = true
        refreshInputControls()
        val ready = goForegroundForPrev()
        if (!ready) goLauncherForPrev()
        panel?.postDelayed({ next() }, if (ready) 0L else 700L)
    }

    private fun overlayCleanupBatch(query: String, batchId: String = "verify-overlay") =
        GoSendWorkflow.Batch(query, batchId, true, GoSendWorkflow.Mode.OVERLAY_CLEANUP)

    private fun namedCleanupQuery(expression: String): String? {
        val catalog = PokemonCatalog.get(app)
        val bounded = GoSendWorkflow.verifiedQuery(overlayCleanupBatch(OverlayGoSearch.normalize(expression)), catalog)
            ?: return null
        val token = bounded.substringBefore('&')
        val name = catalog.goSearchSpeciesName(token, goLocaleProvider())
            ?: catalog.goSearchDexNumber(token)?.toString() ?: return null
        return "$name&${bounded.substringAfter('&')}"
    }

    private fun excludeCleanupNamePrefixes(query: String): String {
        val catalog = PokemonCatalog.get(app)
        val token = query.substringBefore('&')
        val exclusions = if (token.toIntOrNull() == null)
            catalog.goSearchPrefixExclusions(token, goLocaleProvider()) else emptyList()
        val terms = query.split('&')
        return (terms + exclusions.map { "!$it" }.filterNot { excluded ->
            terms.any { it.equals(excluded, ignoreCase = true) }
        }).joinToString("&")
    }

    /** Manual transfer prepares a protected search; the user decides in GO. */
    private fun manualTransferSearch(expression: String) {
        val named = namedCleanupQuery(expression) ?: run {
            setInputStatus("地方フォームや種名を確認できないため、この手動整理を見送りました")
            return
        }
        inputCleanup(excludeCleanupNamePrefixes("$named&!favorite&!#send"), scopedCandidate = true)
    }

    private fun manualDiscardFromLatestBatch() {
        if (controlsLocked) return
        val source = com.example.pokemonivreader.ReaderAutomationStore(app)
        if (source.state != "ready" || source.hasUnverifiedHolds) {
            setInputStatus("確定済みの厳選結果が必要です。保留個体がある場合は確認を先に済ませてください")
            return
        }
        val catalog = PokemonCatalog.get(app)
        val batchId = source.batchId
        val candidates = source.discards().mapNotNull { query ->
            GoSendWorkflow.verifiedQuery(GoSendWorkflow.Batch(query, batchId, true), catalog)
                ?.let { excludeCleanupNamePrefixes(it + "&!favorite&!#send") }
        }.distinct()
        if (candidates.isEmpty()) {
            setInputStatus("安全に検索できる博士送り候補がありません")
            return
        }
        val goLocale = goLocaleProvider()
        val labels = candidates.mapIndexed { i, q ->
            val token = q.substringBefore('&')
            val species = catalog.goSearchSpeciesName(token, goLocale) ?: token
            val constraints = q.split('&').drop(1).filter { part ->
                Regex("(?i)(?:cp[0-9]{1,5}|[0-4](?:attack|defense|hp))").matches(part)
            }.joinToString(" ")
            "${i + 1}. $species　$constraints".take(160)
        }.toTypedArray()
        val dialog = AlertDialog.Builder(app)
            .setTitle("博士送り候補を検索（sendタグなし）")
            // Android AlertDialog message may hide the selection list;
            // show the verified choices, not a competing message view.
            .setItems(labels) { _, which ->
                if (source.state != "ready" || source.hasUnverifiedHolds || source.batchId != batchId)
                    return@setItems
                inputCleanup(candidates[which], scopedCandidate = true)
                toast("GOで対象を確認し、博士送りは手動で行ってください")
            }
            .setNegativeButton("閉じる", null).create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    /** Leave the game on its send-tag results, with no automatic transfer. */
    private fun finishOnSendResults(session: Long, message: String, after: () -> Unit = {}) {
        if ((session >= 0 && session != store.currentSessionId) || panel == null) return
        inputPending = true
        setInputStatus("整理が完了しました。GOのsendタグ一覧を開いています")
        refreshInputControls()
        var received = false
        val generation = continuousGeneration
        val accepted = sendLandingRequester { ok, result ->
            if (received) return@sendLandingRequester
            received = true
            if (generation != continuousGeneration ||
                (session >= 0 && session != store.currentSessionId) || panel == null) return@sendLandingRequester
            inputPending = false
            if (ok) {
                cursor.moveTo(steps.size, steps.size)
                saveFlow()
                setInputStatus(message + "。GOのsend一覧を表示しました")
                after()
                closePanel() // Do not cover the transfer candidates in GO.
            } else {
                stopContinuous()
                setInputStatus(message + "。send一覧の表示を確認できません: " + result)
                refreshInputControls()
            }
        }
        if (!accepted) {
            inputPending = false
            stopContinuous()
            setInputStatus(message + "。send一覧を開くにはユーザー補助を有効にしてください")
            refreshInputControls()
        }
    }

    /** Resume only after an explicit start or the nonce-bound browser result of that start. */
    fun startAutomationBatch(launchFrom: Context = app) {
        if (controlsLocked) return
        val source = com.example.pokemonivreader.ReaderAutomationStore(app)
        if (source.state != "ready" || source.batchId.isBlank()) { toast("今回の厳選結果を先に取得してください"); return }
        val batchId = source.batchId
        val progress = app.getSharedPreferences("automation_apply_v1", Context.MODE_PRIVATE)
        val keeps = source.keeps().takeIf { it.isNotBlank() }?.let {
            val root=org.json.JSONObject(it)
            if (root.getJSONArray("items").length()==0) emptyList() else OverlayPairParser.parse(it)
        }.orEmpty()
        if (progress.getString("batch", "") != batchId) {
            val session = if (keeps.isEmpty()) -1L else store.importPairs(keeps)
            check(progress.edit().clear().putString("batch",batchId).putLong("session",session)
                .putInt("send_index",0)
                .putBoolean("grouped_prev_order_v3", true).commit())
        }
        val session=progress.getLong("session",-1)
        if (session >= 0 && store.items(id = session, filter = OverlayQueueStore.Filter.ALL).any {
                SelectionTagPolicy.ambiguousMankeySource(it.search, it.targetName)
            }) {
            toast("マンキー系統の進化先情報がないため、タグ操作を停止しました")
            return
        }
        if (session >= 0 && store.currentSessionId != session) {
            toast("別の取り込みへ切り替わっています。今回の整理は停止しています"); return
        }
        val items = store.items(id = session, filter = OverlayQueueStore.Filter.ALL)
        val olderPending = olderPendingKeepTags(session)
        if (!independentCandidatesSafe(items)) return
        if (!progress.getBoolean("grouped_prev_order_v3", false) || (session >= 0 && !tagPlanSafe())) {
            toast("旧方式の整理進捗を新方式へ自動移行できません。新しい厳選結果を取り込んでください")
            return
        }
        val catalog = PokemonCatalog.get(app)
        val discardPlan = source.discards().map { query ->
            val batch = GoSendWorkflow.Batch(query, batchId, true)
            GoSendWorkflow.verifiedQuery(batch, catalog)?.let { batch }
        }
        val skippedDiscards = discardPlan.count { it == null }
        val migrationConfirmed = placement.getBoolean("mankey_subprev_migrated_v1", false)
        val orderedPlan = taggedSteps(items).withIndex().filter {
            it.value.prevSources.isNotEmpty() || it.value.item != null
        }
        var nextStep = 0
        var skippedGroups = 0
        stopContinuous()
        readerCleanupMode=false
        if(panel==null) openPanel() else refresh()
        if(panel==null) { toast("オーバーレイの表示を許可して整理を再開してください"); return }
        sequenceRunning = true
        val generation=continuousGeneration
        val initialQueueSession = store.currentSessionId
        fun current() = generation == continuousGeneration && source.batchId == batchId &&
            source.state == "ready" && store.currentSessionId == initialQueueSession && panel != null
        fun stop(message:String) {
            if (generation != continuousGeneration) return
            inputPending=false; stopContinuous(); setInputStatus(message); refreshInputControls()
        }
        fun next() {
            if (!current()) return
            while (nextStep < orderedPlan.size) {
                val (groupIndex, step) = orderedPlan[nextStep]
                if (step.prevSources.isNotEmpty()) {
                    val queries = if (!migrationConfirmed && SelectionTagPolicy.isIndependentTarget(step.target)) null
                        else verifiedPrevQueries(step.prevSources, SelectionTagPolicy.tagForTarget(step.target))
                    // Each reset entry is authoritative for its own group. A flat
                    // cursor can't distinguish verified resets from skipped ones.
                    val key = prevGroupCheckpoint(session, groupIndex)
                    val saved = placement.getInt(key, 0)
                    if (queries != null && saved == queries.size) { nextStep++; continue }
                    if (queries == null || groupAlreadyTagged(step, items, olderPending)) {
                        skippedGroups++
                        nextStep++
                        continue
                    }
                    if (saved !in queries.indices) {
                        stop("グループ解除進捗が不正なため処理を停止しました")
                        return
                    }
                    inputPending = true
                    refreshInputControls()
                    setInputStatus("「${step.target}」前回タグを解除中 ${saved + 1}/${queries.size}")
                    val complete = onceWhileCurrent(::current) { ok, message ->
                        if (!ok) stop(message)
                        else if (!placement.edit().putInt(key, saved + 1).commit())
                            stop("グループの解除進捗を保存できませんでした")
                        else panel?.post { next() }
                    }
                    if (!prevResetRequester(queries[saved], complete))
                        complete(false, "ユーザー補助を有効にしてprev解除から再開してください")
                    return
                }

                val item = checkNotNull(step.item)
                if (store.workflowVerified(item.id, true) &&
                    (item.nickname.isBlank() || store.workflowVerified(item.id, false))) {
                    nextStep++
                    continue
                }
                inputPending = true
                refreshInputControls()
                val complete = onceWhileCurrent(::current) { ok, message ->
                    if (!ok) stop(message)
                    else {
                        val recorded = runCatching {
                            val saved = OverlayInputProgress.record(store, flow, session, item.id, true) &&
                                (item.nickname.isBlank() ||
                                    OverlayInputProgress.record(store, flow, session, item.id, false))
                            if (saved && item.nickname.isNotBlank()) store.recordKeepTag(item.id)
                            saved
                        }.getOrDefault(false)
                        if (!recorded) stop("ゲーム操作は成功しましたが記録できませんでした。画面を確認してください")
                        else {
                            nextStep++
                            refresh()
                            panel?.post { next() }
                        }
                    }
                }
                if (!GoTextInputAccessibilityService.requestIndividual(
                        item, GoTextInputAccessibilityService.Target.SEARCH, continuingSequence = true,
                        reserveKeepTag = { current() && keepTagReserver(item.id, session) },
                        result = complete
                    )) complete(false, "ユーザー補助を有効にして整理を再開してください")
                return
            }
            inputPending = true
            refreshInputControls()
            if (source.hasUnverifiedHolds) {
                stop("保留タグを確認できない個体がいるため、send付与・お気に入り解除を見送りました")
                return
            }
            val from = progress.getInt("send_index", 0)
            if (from !in 0..discardPlan.size) {
                stop("sendの進捗が一致しないため処理を停止しました")
                return
            }
            val index = (from until discardPlan.size).firstOrNull { discardPlan[it] != null }
            if (index == null) {
                inputPending = false
                stopContinuous()
                val message = buildString {
                    append("今回の厳選・send付与・お気に入り解除が完了しました。要確認個体は保持しています")
                    if (skippedGroups > 0) append("。未解除の前回タグが${skippedGroups}グループ残っています")
                    if (skippedDiscards > 0) append("。地方フォームなどを確認できない${skippedDiscards}件は見送りました")
                }
                finishOnSendResults(session, message)
                return
            }
            val batch = checkNotNull(discardPlan[index])
            val complete = onceWhileCurrent(::current) { ok, message ->
                if (!ok) stop(message)
                else if (!progress.edit().putInt("send_index", index + 1).commit())
                    stop("sendの進捗を保存できませんでした")
                else panel?.post { next() }
            }
            if (!GoTextInputAccessibilityService.requestSend(batch,
                    { id -> id == batchId && current() }, complete))
                complete(false, "ユーザー補助を有効にして整理を再開してください")
        }
        retryAction={startAutomationBatch()}
        inputPending = true
        refreshInputControls()
        val entered = onceWhileCurrent(::current) { ok, message -> if (ok) next() else stop(message) }
        if (!automationEntryRequester(launchFrom, ::current, entered))
            entered(false, "ユーザー補助を有効にして整理を再開してください")
    }

    /** Explicit full preparation uses the same checkpoints as each prev group. */
    internal fun startPrevPreparation() {
        if (controlsLocked || readerCleanupMode) {
            setInputStatus("実行中の操作・非厳選画面を終了してからprev解除を開始してください")
            return
        }
        val session = store.currentSessionId
        if (session < 0 || !tagPlanSafe()) return
        val items = store.items(id = session, filter = OverlayQueueStore.Filter.ALL)
        val olderPending = olderPendingKeepTags(session)
        if (!independentCandidatesSafe(items)) return
        val migrationConfirmed = placement.getBoolean("mankey_subprev_migrated_v1", false)
        val plan = mutableListOf<Triple<Int, OverlayQueuePresentation.Step, List<String>>>()
        var unverifiedGroups = 0
        for ((index, step) in taggedSteps(items).withIndex()) {
            if (step.prevSources.isEmpty()) continue
            val safeTarget = (migrationConfirmed || !SelectionTagPolicy.isIndependentTarget(step.target)) &&
                step.prevSources.none { SelectionTagPolicy.ambiguousMankeySource(it, step.target) }
            val queries = if (safeTarget) verifiedPrevQueries(step.prevSources, SelectionTagPolicy.tagForTarget(step.target))
                else null
            if (queries == null) unverifiedGroups++ else plan.add(Triple(index, step, queries))
        }
        val prefs = app.getSharedPreferences("overlay_import_prev_v1", Context.MODE_PRIVATE)
        val checkpointKey = "session_${session}_index_v2"
        stopContinuous()
        if (panel == null) openPanel()
        if (panel == null) { toast("オーバーレイ表示を許可してprev解除を再開してください"); return }
        val generation = continuousGeneration
        fun current() = generation == continuousGeneration && session == store.currentSessionId && panel != null
        fun stop(message: String) {
            if (!current()) return
            inputPending = false
            setInputStatus(message)
            refreshInputControls()
        }
        fun next() {
            if (!current()) return
            var totalDone = 0
            var skippedGroups = unverifiedGroups
            for ((index, step, queries) in plan) {
                val key = prevGroupCheckpoint(session, index)
                val done = placement.getInt(key, 0)
                if (done !in 0..queries.size) {
                    stop("解除済み件数が現在の進化系統と一致しないため停止しました")
                    return
                }
                totalDone += done
                if (done == queries.size) continue
                if (groupAlreadyTagged(step, items, olderPending)) {
                    skippedGroups++
                    continue
                }
                setInputStatus("前回タグを解除中 ${totalDone + 1}/${plan.sumOf { it.third.size }}")
                val complete = onceWhileCurrent(::current) { ok, message ->
                    if (!ok) stop(message)
                    else if (!placement.edit().putInt(key, done + 1).commit())
                        stop("進化系統の解除進捗を保存できませんでした")
                    else if (!prefs.edit().putInt(checkpointKey, totalDone + 1).commit())
                        stop("解除記録の保存に失敗しました。進捗を確認して再開してください")
                    else panel?.post { next() }
                }
                if (!prevResetRequester(queries[done], complete))
                    complete(false, "ユーザー補助を有効にして解除を再開してください")
                return
            }
            if (!prefs.edit().putInt(checkpointKey,totalDone).commit()) {
                stop("解除記録の保存に失敗しました")
                return
            }
            retryAction = null
            stop(if (skippedGroups == 0) "前回タグの解除が完了しました"
                else "前回タグの一括処理が終了しました。対象確認・タグ保護のため${skippedGroups}グループを見送りました")
        }
        retryAction = { startPrevPreparation() }
        inputPending = true
        refreshInputControls()
        val foreground = goForegroundForPrev()
        if (!foreground) goLauncherForPrev()
        panel?.postDelayed({ next() }, if (foreground) 0L else 700L)
    }

    fun openPanel() {
        if (!show() || panel != null) return
        val m = app.resources.displayMetrics
        val defaultWidth = min(dp(240), (m.widthPixels * .62f).toInt()).coerceAtLeast(1)
        val defaultHeight = min(dp(280), (m.heightPixels * .32f).toInt()).coerceAtLeast(1)
        val density = m.density
        val width = (placement.getFloat("panel_width_dp", defaultWidth / density) * density).roundToInt()
        val height = (placement.getFloat("panel_height_dp", defaultHeight / density) * density).roundToInt()
        val p = params(width, height, 0, 0)
        clampPanel(p)
        p.x = (placement.getFloat("panel_x_ratio", 1f).coerceIn(0f, 1f) * (m.widthPixels - p.width - dp(10)).coerceAtLeast(0)).roundToInt() + dp(5)
        p.y = if (placement.contains("panel_y_ratio"))
            (placement.getFloat("panel_y_ratio", 0f).coerceIn(0f, 1f) * (m.heightPixels - p.height - dp(10)).coerceAtLeast(0)).roundToInt() + dp(5)
            else dp(58)
        clampPanel(p)
        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(3), dp(6), dp(3))
            background = background(Color.rgb(24, 31, 41))
        }
        val header = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
        val moveHandle = label("↔", 17f).apply {
            tag = "overlay_move_handle"
            contentDescription = "ドラッグして一覧を移動"
            setOnClickListener { toast("上部をドラッグして移動できます") }
        }
        header.addView(moveHandle, LinearLayout.LayoutParams(0, dp(30), 1f))
        handoffModeButton = button("") {
            if (!controlsLocked) {
                stopContinuous()
                manualHandoffs = !manualHandoffs
                placement.edit().putBoolean("manual_handoffs_v1", manualHandoffs).apply()
                refreshInputControls(notify = false)
                setInputStatus(if (manualHandoffs) "手動：検索式の入力で操作を返します。タグはGOで操作してください" else "自動：名前とタグ付けまで続けます")
            }
        }.also { header.addView(it, LinearLayout.LayoutParams(dp(46), dp(36)).apply { marginEnd = dp(2) }) }
        val learn = button("学習") {}
        learn.contentDescription = "GOのボタンとタグを学習"
        learn.setOnClickListener { showLearningMenu(learn) }
        header.addView(learn, LinearLayout.LayoutParams(dp(42), dp(36)).apply { marginEnd = dp(2) })
        val other = button("その他") {}
        other.setOnClickListener { PopupMenu(app, other).apply { populateOtherMenu(menu); show() } }
        header.addView(other, LinearLayout.LayoutParams(dp(46), dp(36)).apply { marginEnd = dp(2) })
        header.addView(button("×") { closePanel() }.apply {
            contentDescription = "丸いボタンに縮める"
        }, LinearLayout.LayoutParams(dp(32), dp(36)))
        root.addView(header)
        // Learning feedback returns here even if the search panel is being recreated.
        inputStatus = label("", 11f).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            maxLines = 5
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.rgb(34, 44, 56))
                cornerRadius = dp(6).toFloat()
            }
            setTextColor(Color.rgb(205, 227, 216))
        }.also { root.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(2) }) }
        renderInputStatus(learningResult)
        confirmButton = button("種・姿・IVを照合して続行") {
            val action = confirmAction
            confirmAction = null; confirmButton?.visibility = View.GONE
            action?.invoke()
        }.apply { visibility = View.GONE }.also { root.addView(it, LinearLayout.LayoutParams(-1, dp(44))) }
        val navigation = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
        stopButton = button("停止") { stopInput() }.also {
            navigation.addView(it, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })
        }
        previousButton = button("戻る") { previousIndividual() }.also {
            navigation.addView(it, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(4) })
        }
        nextButton = button("次へ") { nextIndividual() }.also {
            navigation.addView(it, LinearLayout.LayoutParams(0, dp(34), 1f))
        }
        root.addView(navigation)
        // Per-evolution-group Prev buttons now live immediately above their IV rows.
        // Global reset remains available in the "整理" submenu for legacy sessions.
        val l = ListView(app).apply { dividerHeight = dp(1); cacheColorHint = Color.TRANSPARENT }
        list = l; adapter = QueueAdapter(); l.adapter = adapter
        root.addView(l, LinearLayout.LayoutParams(-1, 0, 1f))
        val resizeHandle = label("↙", 16f).apply {
            tag = "overlay_resize_handle"
            gravity = Gravity.CENTER
            contentDescription = "ドラッグしてサイズ変更。左へ広げる、下へ伸ばす"
            setOnClickListener { toast("左下のつまみをドラッグしてサイズ変更できます") }
        }
        root.addView(resizeHandle, LinearLayout.LayoutParams(dp(36), dp(28)))
        attachPanelGesture(moveHandle, root, p, resize = false)
        attachPanelGesture(resizeHandle, root, p, resize = true)
        try {
            // Hide the bubble before adding the panel (Android touch opacity limit).
            bubble?.visibility = View.GONE
            manager.addView(root, p); panel = root; panelParams = p
            SearchOverlayVisibility.setActive(true)
            refresh()
            if (!restoreIndividualRowsAfterCleanup()) restoreListScroll()
        } catch (e: Exception) { bubble?.visibility = View.VISIBLE; toast("一覧を表示できません: ${e.localizedMessage}") }
    }
    private fun Menu.action(label: String, enabled: Boolean = true, run: () -> Unit) {
        add(Menu.NONE, View.generateViewId(), Menu.NONE, label).apply {
            isEnabled = enabled
            setOnMenuItemClickListener {
                if (isEnabled) run()
                true
            }
        }
    }

    private fun requestLearning(role: GoButtonTemplateStore.Role? = null, replace: Int? = null,
                                preview: Boolean = false) {
        val host = panel ?: return
        // Allow the popup/modal to leave the screen before a screenshot is requested.
        host.postDelayed({
            if (panel === host && !controlsLocked && !GoTextInputAccessibilityService.learn(role, replace, preview))
                setInputStatus("Androidのユーザー補助で「厳選テキスト入力」を有効にしてください")
        }, 250L)
    }

    private fun showLearningMenu(anchor: View) {
        GoButtonLearningMenu.show(
            context = app,
            anchor = anchor,
            isLocked = { controlsLocked },
            showDialog = ::showRecoveryDialog,
            onStatus = ::setInputStatus,
            onRename = { role ->
                if (!GoTextInputAccessibilityService.renameLearned(role)) {
                    setInputStatus("Androidのユーザー補助で「厳選テキスト入力」を有効にしてください")
                }
            },
            requestLearning = ::requestLearning,
        )
    }

    private fun showRecoveryDialog(dialog: AlertDialog) {
        recoveryDialog?.dismiss()
        recoveryDialog = dialog
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        dialog.setOnDismissListener { if (recoveryDialog === dialog) recoveryDialog = null }
        if (runCatching { dialog.show() }.isFailure) recoveryDialog = null
    }

    private fun openNameOkRecovery(message: String, logExcerpt: String, keyboardBlocked: Boolean) =
        showCleanupRecovery(message, logExcerpt, GoLearningTarget.NAME_OK, "名前変更のOKで停止", keyboardBlocked)

    internal fun showCleanupRecovery(message: String, logExcerpt: String, target: GoLearningTarget?,
                                     title: String = "自動整理で停止", keyboardBlocked: Boolean = false) {
        if (panel == null || controlsLocked) return
        val log = GoAutomationMetrics(app).latestStopReport() ?: logExcerpt
        val body = buildString {
            append(message)
            if (keyboardBlocked) append("\n\nキーボード操作または検索画面の継続を確認できませんでした。")
            if (logExcerpt.isNotBlank()) append("\n\n直前のログ\n").append(logExcerpt)
        }
        val builder = AlertDialog.Builder(app)
            .setTitle(if (keyboardBlocked) "キーボード操作で停止" else title)
            .setMessage(body)
            .setNegativeButton("閉じる", null)
        if (log.isNotBlank()) builder.setNeutralButton("ログをコピー", null)
        if (keyboardBlocked && GoTextInputAccessibilityService.hasStoppedKeyboardFrame()) {
            builder.setPositiveButton("停止画面から学習") { _, _ -> chooseStoppedKeyboardTarget() }
        } else if (keyboardBlocked && target?.keyboardControl == true) {
            builder.setPositiveButton("現在の画面で学習") { _, _ -> learnRecoveryTarget(target) }
        } else if (keyboardBlocked) {
            builder.setPositiveButton("キーボードの閉じ方") { _, _ ->
                showRecoveryDialog(AlertDialog.Builder(app)
                    .setTitle("キーボードの閉じ方")
                    .setMessage("キーボードの非表示キーを確認し、検索条件が残っていることを確かめてください。")
                    .setPositiveButton("閉じる", null).create())
            }
        } else if (target != null) {
            val action = when {
                target.keyboardControl -> "キーボード操作を学習"
                target == GoLearningTarget.NAME_OK -> "OKを学習"
                else -> "学習"
            }
            builder.setPositiveButton(action) { _, _ -> learnRecoveryTarget(target) }
        }
        val dialog = builder.create()
        showRecoveryDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            runCatching { com.example.pokemonivreader.ClipboardCopy.write(app, "自動入力の停止ログ", log) }
                .onSuccess { toast("直前のログをコピーしました") }
                .onFailure { toast("停止ログをコピーできませんでした") }
        }
    }

    private fun learnFromStoppedKeyboard(target: GoLearningTarget, replacement: Int? = null) {
        val host = panel ?: return
        host.postDelayed({
            if (panel === host && !controlsLocked &&
                !GoTextInputAccessibilityService.learnStoppedKeyboard(
                    GoButtonTemplateStore(app).role(target), replacement)) {
                setInputStatus("停止画面を復元できません。キーボードを再表示して学習してください")
            }
        }, 250L)
    }

    private fun chooseStoppedKeyboardTarget() {
        val roles = GoCleanupKeyboard.Control.entries.map { it.target }
        showRecoveryDialog(AlertDialog.Builder(app).setTitle("停止した画面から学習")
            .setItems(roles.map { it.title }.toTypedArray()) { _, which ->
                val target = roles[which]
                val store = GoButtonTemplateStore(app)
                val count = store.count(store.role(target))
                if (count < GoButtonTemplateStore.MAX_SAMPLES) learnFromStoppedKeyboard(target)
                else showRecoveryDialog(AlertDialog.Builder(app).setTitle("置き換える見本")
                    .setItems(Array(count) { "見本 ${it + 1} を置き換える" }) { _, index ->
                        learnFromStoppedKeyboard(target, index)
                    }.setNegativeButton("戻る", null).create())
            }.setNegativeButton("戻る", null).create())
    }

    private fun learnRecoveryTarget(target: GoLearningTarget) {
        GoButtonLearningMenu.learnRecoveryTarget(app, target, ::showRecoveryDialog, ::requestLearning)
    }

    private fun openAutomationLog() {
        val metrics = GoAutomationMetrics(app)
        com.example.pokemonivreader.DiagnosticLogDialog.show(app, "自動入力の停止ログ", metrics.exportReport()) {
            stopInput()
            app.startActivity(android.content.Intent(app, GoAutomationLogActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    internal fun populateOtherMenu(menu: Menu) {
        menu.addSubMenu("表示").apply {
            action(if (showNavigation) "戻る・次へを隠す" else "戻る・次へを表示", !controlsLocked) {
                if (!controlsLocked) {
                    showNavigation = !showNavigation
                    refreshInputControls(notify = false)
                }
            }
            action(if (readerCleanupMode) "厳選へ切替" else "非厳選へ切替", !controlsLocked) {
                if (!controlsLocked) {
                    if (readerCleanupMode) {
                        readerCleanupMode = false
                        refresh()
                        restoreListScroll()
                    } else showReaderCleanup()
                }
            }
        }
        menu.addSubMenu("自動入力").apply {
            action(if (continuous) "連続 ON" else "連続 OFF", !controlsLocked) {
                if (!controlsLocked) {
                    stopContinuous()
                    continuous = !continuous
                    placement.edit().putBoolean("continuous_enabled_v1", continuous).apply()
                    refreshInputControls()
                }
            }
            action("再試行", !controlsLocked && retryAction != null) {
                if (!controlsLocked) retryAction?.invoke()
            }
            action(if (skipUnreadableCp) "CP未読取でも進む ON" else "CP未読取でも進む OFF", !controlsLocked) {
                if (!controlsLocked) {
                    skipUnreadableCp = !skipUnreadableCp
                    placement.edit().putBoolean("skip_unreadable_search_cp", skipUnreadableCp).apply()
                }
            }
        }
        menu.addSubMenu("整理").apply {
            action("マンキー系統：独立タグ移行を確認", !controlsLocked) {
                if (!controlsLocked) {
                    val dialog = android.app.AlertDialog.Builder(app)
                        .setTitle("prev / subprev の移行確認")
                        .setMessage("以前のマンキー系統のprevタグは、オコリザル用かコノヨザル用か自動判定できません。Pokémon GOで既存個体のタグを手動確認し、コノヨザル用をsubprevへ移行し、subprevタグが存在することを確認してください。未確認なら続行しないでください。\n\nこの操作は既存GOタグの変更を行いません。")
                        .setNegativeButton("まだ確認していない", null)
                        .setPositiveButton("GOで確認・移行済み") { _, _ ->
                            if (placement.edit().putBoolean("mankey_subprev_migrated_v1", true).commit())
                                setInputStatus("独立タグの移行確認を保存しました")
                            else setInputStatus("移行確認を保存できませんでした")
                        }.create()
                    dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    dialog.show()
                }
            }
            action("確認待ちの保護予約を見る", !controlsLocked) { showPendingKeepTags() }
            action("前回タグを一括解除", !controlsLocked) { startPrevPreparation() }
            action("今回の整理を開始・再開", !controlsLocked) { startAutomationBatch() }
            action("手動で博士送り（sendタグなし）", !controlsLocked) { manualDiscardFromLatestBatch() }
        }
        menu.addSubMenu("診断・履歴").apply {
            action("自動入力の停止ログ") { openAutomationLog() }
            action("自動入力の計測結果") {
                val metrics = GoAutomationMetrics(app)
                val dialog = android.app.AlertDialog.Builder(app).setTitle("自動入力の計測結果")
                    .setMessage(metrics.report()).setPositiveButton("閉じる", null)
                    .setNeutralButton("集計を消去") { _, _ -> metrics.clear() }.create()
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                dialog.show()
            }
            action("読取履歴を削除・復元", !controlsLocked) {
                if (!controlsLocked) com.example.pokemonivreader.ReaderHistoryDialog.show(app)
            }
        }
        menu.action("パネルを閉じる") { closePanel() }
    }

    private fun stopInput() {
        val wasRunning = inputPending || sequenceRunning
        stopContinuous()
        inputPending = false
        if (!wasRunning || !restoreIndividualRowsAfterCleanup()) refreshInputControls()
        GoTextInputAccessibilityService.stopByUser()
    }

    private fun restoreIndividualRowsAfterCleanup(): Boolean {
        if (readerCleanupMode || steps.getOrNull(cursor.position)?.item != null ||
            steps.none { it.item != null } || cursor.position !in 1..steps.size) return false
        cursor.moveTo(0, steps.size)
        saveFlow()
        refresh(reload = false)
        list?.setSelectionFromTop(0, 0)
        return true
    }

    private fun clampPanel(p: WindowManager.LayoutParams) {
        val m = app.resources.displayMetrics
        val margin = min(dp(5), min(m.widthPixels, m.heightPixels) / 4)
        val maxWidth = (m.widthPixels - margin * 2).coerceAtLeast(1)
        val maxHeight = min((m.heightPixels - margin * 2).coerceAtLeast(1), min(dp(640), (m.heightPixels * .8f).toInt()).coerceAtLeast(1))
        p.width = p.width.coerceIn(min(dp(196), maxWidth), maxWidth)
        p.height = p.height.coerceIn(min(dp(220), maxHeight), maxHeight)
        p.x = p.x.coerceIn(margin, (m.widthPixels - p.width - margin).coerceAtLeast(margin))
        p.y = p.y.coerceIn(margin, (m.heightPixels - p.height - margin).coerceAtLeast(margin))
    }
    private fun savePanel(p: WindowManager.LayoutParams) {
        val m = app.resources.displayMetrics
        val maxX = (m.widthPixels - p.width - dp(10)).coerceAtLeast(0)
        val maxY = (m.heightPixels - p.height - dp(10)).coerceAtLeast(0)
        placement.edit()
            .putFloat("panel_width_dp", p.width / m.density)
            .putFloat("panel_height_dp", p.height / m.density)
            .putFloat("panel_x_ratio", if (maxX == 0) 0f else ((p.x - dp(5)).toFloat() / maxX).coerceIn(0f, 1f))
            .putFloat("panel_y_ratio", if (maxY == 0) 0f else ((p.y - dp(5)).toFloat() / maxY).coerceIn(0f, 1f))
            .apply()
    }
    private fun attachPanelGesture(handle: View, root: View, p: WindowManager.LayoutParams, resize: Boolean) {
        handle.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f; var downY = 0f
            var startX = 0; var startY = 0; var startWidth = 0; var startHeight = 0
            var dragged = false
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY
                        startX = p.x; startY = p.y; startWidth = p.width; startHeight = p.height
                        dragged = false; return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX; val dy = event.rawY - downY
                        if (abs(dx) > dp(5) || abs(dy) > dp(5)) dragged = true
                        if (dragged) {
                            // Bottom-left resizing grows toward the free area to the left
                            // when the panel starts against the right edge.
                            p.x = startX + dx.toInt()
                            p.y = if (resize) startY else startY + dy.toInt()
                            if (resize) {
                                p.width = startWidth - dx.toInt()
                                p.height = startHeight + dy.toInt()
                                clampPanel(p)
                                p.x = startX + startWidth - p.width
                                p.y = startY
                            }
                            clampPanel(p)
                            try { manager.updateViewLayout(root, p) } catch (_: Exception) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragged) savePanel(p)
                        else if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                        return true
                    }
                }
                return false
            }
        })
    }
    fun closePanel() {
        recoveryDialog?.dismiss(); recoveryDialog = null
        stopContinuous()
        if (inputPending) GoTextInputAccessibilityService.cancel()
        inputPending = false
        if (!readerCleanupMode) saveListScroll()
        panel?.let { try { manager.removeView(it) } catch (_: Exception) {} }
        panel = null; panelParams = null; list = null; adapter = null; rows = emptyList()
        inputStatus = null
        confirmAction = null; confirmButton = null; stopButton = null; handoffModeButton = null
        prevPreparationButton = null
        retryAction = null
        previousButton = null; nextButton = null; showNavigation = false; steps = emptyList(); visibleSteps = emptyList(); itemsById.clear(); itemPositions.clear()
        bubble?.visibility = View.VISIBLE
        SearchOverlayVisibility.setActive(false)
    }
    fun togglePanel() { if (panel == null) openPanel() else closePanel() }
    /** Stop both windows; leave SQLite history and the independent OCR service intact. */
    fun hide() {
        hideDropTarget()
        closePanel()
        bubble?.let { try { manager.removeView(it) } catch (_: Exception) {} }
        bubble = null
    }
    fun destroy() { hide(); store.close(); instance = null }
    fun onImported(): Boolean {
        stopContinuous()
        readerCleanupMode = false
        if (inputPending) GoTextInputAccessibilityService.cancel()
        inputPending = false
        // Newly received session always opens without restoring the previous session scroll.
        if (panel != null) {
            refresh()
            if (!restoreIndividualRowsAfterCleanup()) list?.setSelectionFromTop(0, 0)
        } else {
            openPanel()
        }
        return panel != null
    }
    private fun refresh(reload: Boolean = true) {
        val position = list?.firstVisiblePosition ?: 0
        val top = list?.getChildAt(0)?.top ?: 0
        if (readerCleanupMode) {
            // Reader cleanup has its own cursor and never changes selection history.
            hasSessionItems = false
            rows = emptyList()
            readerCursor.clamp(readerCleanupItems.size)
        } else {
            loadFlow()
            if (reload) {
                val all = store.items(filter = OverlayQueueStore.Filter.ALL)
                hasSessionItems = all.isNotEmpty()
                itemsById.clear(); all.associateByTo(itemsById) { it.id }
                steps = taggedSteps(all)
                if (migratePrevGroupCursor) {
                    val oldRows = steps.withIndex().filter { it.value.prevSources.isEmpty() }
                    val oldPosition = cursor.position
                    val mapped = if (oldPosition == 0) 0
                        else oldRows.getOrNull(oldPosition)?.index ?: steps.size
                    // Never migrate a cursor past the newly inserted reset row.
                    val migrated = if (mapped > 0 && mapped < steps.size &&
                        steps[mapped - 1].prevSources.isNotEmpty() &&
                        !prevGroupDone(mapped - 1, steps[mapped - 1])) mapped - 1 else mapped
                    cursor.moveTo(migrated, steps.size)
                    migratePrevGroupCursor = false
                    saveFlow()
                }
                if (migrateCleanupCursor) {
                    // Legacy cursors counted two commands per species. Keep individual progress,
                    // and restart the cleanup suffix with its new, idempotent workflow.
                    val cleanupStart = steps.indexOfFirst { it.expression != null }.takeIf { it >= 0 } ?: steps.size
                    cursor.moveTo(minOf(cursor.position, cleanupStart), steps.size)
                    migrateCleanupCursor = false
                }
                if (recoverLegacyCleanupRows) {
                    if (cursor.position in 1 until steps.size && steps[cursor.position].item == null &&
                        steps.any { it.item != null }) cursor.moveTo(0, steps.size)
                    recoverLegacyCleanupRows = false
                    saveFlow()
                }
                itemPositions.clear()
                steps.forEachIndexed { index, step -> step.item?.let { itemPositions[it.id] = index } }
                rows = steps.mapNotNull { it.item }
            }
            cursor.clamp(steps.size)
            visibleSteps = steps.subList(cursor.position, steps.size)
        }
        refreshInputControls(notify = false)
        adapter?.notifyDataSetChanged()
        val count = if (readerCleanupMode) readerCleanupItems.size - readerCursor.position else visibleSteps.size
        if (count > 0) list?.setSelectionFromTop(position.coerceAtMost(count - 1), top)
    }
    /** Busy-state changes do not change SQLite rows or the current scroll position. */
    private fun refreshInputControls(notify: Boolean = true) {
        val running = controlsLocked || sequenceRunning
        stopButton?.isEnabled = running
        stopButton?.visibility = if (running) View.VISIBLE else View.GONE
        prevPreparationButton?.visibility = View.GONE
        handoffModeButton?.isEnabled = !controlsLocked
        handoffModeButton?.text = if (manualHandoffs) "手動" else "自動"
        handoffModeButton?.contentDescription = if (manualHandoffs)
            "手動。検索式の入力で操作を返し、タグはGOで操作"
        else "工程のつなぎ：自動。名前入力とタグ付けまで続ける"
        previousButton?.visibility = if (showNavigation && !running) View.VISIBLE else View.GONE
        nextButton?.visibility = if (showNavigation && !running) View.VISIBLE else View.GONE
        val position = if (readerCleanupMode) readerCursor.position else cursor.position
        val size = if (readerCleanupMode) readerCleanupItems.size else steps.size
        previousButton?.isEnabled = position > 0 && !controlsLocked
        nextButton?.text = "次へ"
        nextButton?.isEnabled = !inputPending && !sequenceRunning && position < size
        if (notify) adapter?.notifyDataSetChanged()
    }
    private fun requestManualInput(item: OverlayQueueStore.Item, search: Boolean) {
        // Read-only manual search is available for legacy SQLite sessions: it
        // never renames or edits tags. Legacy automated tagging still fails closed.
        if (!search && (!tagPlanSafe() ||
            SelectionTagPolicy.ambiguousMankeySource(item.search, item.targetName))) return
        stopContinuous()
        val generation = continuousGeneration
        val session = store.currentSessionId
        retryAction = { requestGoInput(store.item(item.id, session) ?: item, search) }
        inputPending = true
        setInputStatus(if (search) "検索式を入力中" else "名前を入力中")
        refreshInputControls()
        fun complete(ok: Boolean, message: String) {
            if (generation != continuousGeneration || session != store.currentSessionId) return
            inputPending = false
            if (ok) retryAction = null
            setInputStatus(if (ok && search) "検索式を入力しました。GOで対象を選んでください" else message)
            refreshInputControls()
        }
        val goLocale = goLocaleProvider()
        val catalog = com.example.pokemonivreader.PokemonCatalog.get(app)
        val normalized = OverlayGoSearch.normalize(item.search)
        val token = normalized.substringBefore('&').trim()
        val manualSearch = if (search) {
            OverlayGoSearch.namedIndividual(normalized) { number ->
                catalog.goSearchSpeciesName(number, goLocale)
            } ?: if (goLocale == null && OverlayGoSearch.hasNumericSpecies(normalized) &&
                inputRequester === defaultInputRequester && catalog.isKnownGoSearchSpecies(token)
            ) normalized else null
        } else item.search
        if (search && manualSearch == null) {
            complete(false, if (goLocale == null && OverlayGoSearch.hasNumericSpecies(normalized))
                "Pokémon GOの表示言語を確認できないため、図鑑番号からポケモン名へ変換できませんでした"
            else "図鑑番号をポケモン名に変換できないため、検索入力を停止しました")
            return
        }
        val finished = onceWhileCurrent({ generation == continuousGeneration && session == store.currentSessionId }, ::complete)
        // Once the GO editor/list is handed back, the user can apply a tag without a callback.
        if (item.nickname.isNotBlank() && !keepTagReserver(item.id, session)) {
            finished(false, "保護予約を保存できなかったため、手動操作への引き渡しを見送りました")
            return
        }
        val accepted = if (search) inputRequester(GoTextInputAccessibilityService.Target.SEARCH, manualSearch!!, finished)
            else individualRequester(item, GoTextInputAccessibilityService.Target.NICKNAME, finished)
        if (!accepted) finished(false, "自動入力にはAndroidのユーザー補助「厳選テキスト入力」の有効化が必要です")
    }

    /** A check records a verified detail screen or saved name plus return to the list. */
    private fun requestGoInput(item: OverlayQueueStore.Item, search: Boolean, directlyStarted: Boolean = true) {
        if (controlsLocked || (itemPositions[item.id] ?: -1) < cursor.position) return
        if (directlyStarted) stopContinuous()
        val session = store.currentSessionId
        if (store.item(item.id, session) == null) return
        // Do not reinterpret "検索 自動入力" as an evolution-family prev reset.
        // This guard also applies when individualRequester is replaced in tests.
        if (search && !GoSearchIntent.isIndividualQuery(item.search)) {
            stopContinuous()
            setInputStatus("個体検索には個体用の検索式を指定してください。prev / subprev解除は別の専用工程です")
            return
        }
        if (manualHandoffs && search) {
            requestManualInput(item, search) // No tag writes, including in old sessions.
            return
        }
        if (!tagPlanSafe() || (directlyStarted &&
            !independentCandidatesSafe(store.items(id = session, filter = OverlayQueueStore.Filter.ALL)))) return
        if (SelectionTagPolicy.ambiguousMankeySource(item.search, item.targetName)) {
            setInputStatus("マンキー系統の進化先が不明のため処理を停止しました")
            return
        }
        // Missing prev preparation does not block individual search/rename.
        // These entrypoints never perform a reset; any leftover old prev tags
        // remain protected by !#prev / !#subprev in downstream cleanup.
        if (manualHandoffs) { requestManualInput(item, search); return }
        if (continuous && !sequenceRunning) {
            // Start the individual sequence at the selected item. Never dispatch
            // a prev-reset command from the per-Pokémon search button.
            sequenceRunning = true
            cursor.moveTo(itemPositions.getValue(item.id), steps.size)
        }
        val target = if (search) GoTextInputAccessibilityService.Target.SEARCH
            else GoTextInputAccessibilityService.Target.NICKNAME
        val runGeneration = continuousGeneration
        inputPending = true
        retryAction = { requestGoInput(store.item(item.id, session) ?: item, search) }
        setInputStatus(null)
        refreshInputControls()
        var delivered = false
        val accepted = individualRequester(item, target) { ok, message ->
            if (delivered || runGeneration != continuousGeneration) return@individualRequester
            delivered = true
            inputPending = false
            confirmAction = null; confirmButton?.visibility = View.GONE
            var recorded = false
            if (session == store.currentSessionId) {
                if (ok) try {
                    recorded = OverlayInputProgress.record(store, flow, session, item.id, search)
                    if(recorded && search && item.nickname.isNotBlank()) recorded = OverlayInputProgress.record(store, flow, session, item.id, false)
                    if (recorded && item.nickname.isNotBlank()) store.recordKeepTag(item.id)
                    val updated = store.item(item.id, session)
                    if (updated != null) itemsById[item.id] = updated
                    val complete = updated != null && store.workflowVerified(item.id, true) &&
                        (updated.nickname.isBlank() || store.workflowVerified(item.id, false))
                    if (complete && (continuous || visibleSteps.firstOrNull()?.item?.id == item.id))
                        cursor.moveTo(itemPositions.getValue(item.id) + 1, steps.size)
                    saveFlow()
                } catch (e: Exception) { recorded = false }
                setInputStatus(if (ok && !recorded) "ゲーム操作は成功しましたが記録できませんでした。画面を確認してください" else message)
            }
            if (ok && !recorded) GoAutomationMetrics(app).recordStop(target.name, "RECORD", "reason_other", detail = "progress_write_failed")
            if (!ok || !recorded || session != store.currentSessionId) stopContinuous()
            if (session != store.currentSessionId) refresh()
            else if (ok && recorded && continuous && runGeneration == continuousGeneration) continueWithNextStep(session)
            else if (ok) refresh(reload = false) else refreshInputControls()
        }
        if (!accepted) {
            GoAutomationMetrics(app).recordStop(target.name, "SERVICE", "reason_input", detail = "service_unavailable")
            inputPending = false
            stopContinuous()
            refreshInputControls()
            setInputStatus("自動入力にはAndroidのユーザー補助「厳選テキスト入力」の有効化が必要です")
        }
    }
    /** Advance from verified individuals to the final cleanup section. */
    private fun continueWithNextStep(session: Long) {
        while(cursor.position<steps.size) {
            val index=cursor.position
            val step=steps[index]
            val item=step.item?.let{itemsById[it.id]}
            if(item!=null && store.workflowVerified(item.id,true) &&
                (item.nickname.isBlank() || store.workflowVerified(item.id,false))) {
                cursor.next(steps.size);continue
            }
            if (step.prevSources.isNotEmpty()) {
                if (!tagPlanSafe()) return
                if (prevGroupDone(index, step)) { cursor.next(steps.size); continue }
                if (prevSequenceAuthorized && !groupAlreadyTagged(step, itemsById.values, olderPendingKeepTags(session)) &&
                    (placement.getBoolean("mankey_subprev_migrated_v1", false) ||
                        !SelectionTagPolicy.isIndependentTarget(step.target)) &&
                    verifiedPrevQueries(step.prevSources, SelectionTagPolicy.tagForTarget(step.target)) != null) {
                    // Only an explicit prev workflow may dispatch another reset.
                    saveFlow(); refresh(reload = false)
                    val generation = continuousGeneration
                    panel?.post {
                        if (sequenceRunning && prevSequenceAuthorized &&
                            generation == continuousGeneration && session == store.currentSessionId)
                            startPrevGroup(index, directlyStarted = false)
                    }
                    return
                }
                // Individual search intentionally skips incomplete resets rather than
                // stopping the user's entire sequence. It never marks reset as done.
                cursor.next(steps.size)
                saveFlow()
                continue
            }
            if(item==null && step.expression==null) {cursor.next(steps.size);continue}
            saveFlow();refresh(reload=false)
            val generation=continuousGeneration
            panel?.post {
                if(continuous && sequenceRunning && generation==continuousGeneration && !inputPending && session==store.currentSessionId) {
                    if (item != null) requestGoInput(item, !store.workflowVerified(item.id, true), directlyStarted = false)
                    else requestCommand(step,index)
                }
            }
            return
        }
        stopContinuous();saveFlow();refresh(reload=false)
        val incompletePrev = steps.withIndex().count { (index, step) ->
            step.prevSources.isNotEmpty() && !prevGroupDone(index, step)
        }
        val skippedCleanup = steps.count { it.expression?.let { query -> namedCleanupQuery(query) == null } == true }
        setInputStatus(buildString {
            append("連続処理が終了しました")
            if (incompletePrev > 0) append("。未解除のprev / subprevが${incompletePrev}グループ残っています")
            if (skippedCleanup > 0) append("。地方フォームなどを確認できない整理${skippedCleanup}件は見送りました")
        })
    }
    private fun requestCommand(step: OverlayQueuePresentation.Step, index: Int, directlyStarted: Boolean = false) {
        if (controlsLocked || readerCleanupMode || steps.getOrNull(index) != step) return
        val expression = step.expression ?: return
        val session = store.currentSessionId
        val named = namedCleanupQuery(expression)
        if (manualHandoffs) {
            if (named == null) setInputStatus("地方フォームや種名を確認できないため、この手動整理を見送りました")
            else inputCleanup(excludeCleanupNamePrefixes(named), scopedCandidate = true)
            return
        }
        if (!tagPlanSafe()) return
        if (!directlyStarted && rows.any { it.nickname.isNotBlank() && !store.keepTagVerified(it.id) }) {
            stopContinuous()
            if (!restoreIndividualRowsAfterCleanup()) refreshInputControls()
            setInputStatus("未処理の重要個体があるため、検索からの連続処理はここで終了しました")
            return
        }
        val catalog = PokemonCatalog.get(app)
        val dex = named?.substringBefore('&')?.let(catalog::goSearchDexNumber)
        if (named == null || dex == null) {
            if (sequenceRunning && !directlyStarted) {
                cursor.moveTo(index + 1, steps.size)
                saveFlow()
                continueWithNextStep(session)
            } else {
                setInputStatus("地方フォームや種名を確認できないため、この整理を見送りました")
            }
            return
        }
        sequenceRunning = true
        val generation = continuousGeneration
        val batchId = "overlay-$session-$generation"
        fun current() = generation == continuousGeneration && session == store.currentSessionId && panel != null
        val cursorMoved = cursor.position != index
        cursor.moveTo(index, steps.size)
        saveFlow()
        inputPending = true
        setInputStatus("整理対象のお気に入りとsendを確認中")
        if (cursorMoved) refresh(reload = false) else refreshInputControls()
        retryAction = { requestCommand(step, index, directlyStarted) }
        var delivered = false
        fun complete(ok: Boolean, message: String) {
            if (delivered) return
            delivered = true
            if (!current()) return
            inputPending = false
            if (!ok) {
                stopContinuous()
                setInputStatus(message)
                if (!restoreIndividualRowsAfterCleanup()) refreshInputControls()
                return
            }
            retryAction = null
            val next = (index + 1 until steps.size).firstOrNull {
                steps[it].expression?.let(::namedCleanupQuery) != null
            }
            cursor.moveTo(next ?: steps.size, steps.size)
            saveFlow()
            if (next == null) {
                stopContinuous()
                val message = "自動整理とsendタグ・お気に入りの確認が完了しました"
                val skipped = steps.count { row ->
                    row.expression?.let { namedCleanupQuery(it) == null } == true
                }
                finishOnSendResults(session, if (skipped == 0) message
                    else "$message。地方フォームなどを確認できない${skipped}件は見送りました")
            } else {
                refresh(reload = false)
                panel?.post {
                    if (current() && cursor.position == next)
                        requestCommand(steps[next], next, directlyStarted)
                }
            }
        }
        val exactQuery = "$dex&${named.substringAfter('&')}"
        val batch = overlayCleanupBatch(exactQuery, batchId)
        if (!cleanupRequester(batch, { id -> id == batchId && current() }, ::complete)) {
            val message = "ユーザー補助を有効にして、このコマンドから再開してください"
            GoAutomationMetrics(app).recordStop("BULK", "SERVICE", "reason_input", detail = "service_unavailable")
            complete(false, message)
            showCleanupRecovery(message, "", GoLearningTarget.SEARCH)
        }
    }
    private fun inputCleanup(value: String, scopedCandidate: Boolean = false) {
        if (controlsLocked) return
        stopContinuous()
        val generation = continuousGeneration
        val session = store.currentSessionId
        inputPending = true
        setInputStatus(null)
        refreshInputControls()
        val completed = onceWhileCurrent({
            generation == continuousGeneration && session == store.currentSessionId && panel != null
        }) { _, message ->
            inputPending = false
            refreshInputControls()
            setInputStatus(message)
        }
        val accepted = if (scopedCandidate && inputRequester === defaultInputRequester)
            GoTextInputAccessibilityService.requestCleanupSearch(value, completed)
        else inputRequester(GoTextInputAccessibilityService.Target.SEARCH, value, completed)
        if (!accepted) {
            GoAutomationMetrics(app).recordStop("SEARCH", "SERVICE", "reason_input", detail = "service_unavailable")
            completed(false, "Androidのユーザー補助「厳選テキスト入力」を有効にしてください")
        }
    }
    private data class CleanupHolder(val title: TextView, val action: Button, val manual: Button)
    private data class ReaderCleanupHolder(
        val title: TextView, val preview: TextView, val input: Button, val copy: Button
    )
    private data class Holder(
        val heading: TextView, val title: TextView, val preview: TextView, val inputSearch: Button, val inputNick: Button
    )
    private inner class QueueAdapter : BaseAdapter() {
        override fun getCount() = if (readerCleanupMode) readerCleanupItems.size - readerCursor.position else visibleSteps.size
        override fun getItem(position: Int): Any =
            if (readerCleanupMode) readerCleanupItems[position + readerCursor.position] else visibleSteps[position]
        override fun getItemId(position: Int): Long =
            if (readerCleanupMode) -1_000_000L - position - readerCursor.position
            else visibleSteps[position].item?.id ?: -(position + cursor.position + 1L)
        override fun getViewTypeCount() = 3
        override fun getItemViewType(position: Int) =
            if (readerCleanupMode) 2 else if (visibleSteps[position].item != null) 0 else 1
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (readerCleanupMode) return readerCleanupView(position, convertView)
            if (visibleSteps[position].item == null) return cleanupView(position, convertView)
            val root = (convertView as? LinearLayout)?.takeIf { it.tag is Holder } ?: LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, dp(2), 0, dp(5))
                val heading = label("", 13f); addView(heading, LinearLayout.LayoutParams(-1, -2))
                val title = label("", 13f); addView(title, LinearLayout.LayoutParams(-1, -2))
                val preview = label("", 11f).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(Color.rgb(200, 221, 207))
                }
                addView(preview, LinearLayout.LayoutParams(-1, -2))
                val inputButtons = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
                val inputSearch = button("検索 自動入力") {}
                val inputNick = button("名前 自動入力") {}
                inputButtons.addView(inputSearch, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(3) })
                inputButtons.addView(inputNick, LinearLayout.LayoutParams(0, dp(36), 1f))
                addView(inputButtons)
                tag = Holder(heading, title, preview, inputSearch, inputNick)
            }
            val h = root.tag as Holder; val step = visibleSteps[position]; val item = itemsById[step.item!!.id] ?: requireNotNull(step.item)
            h.heading.text = step.target
            h.heading.visibility = if (position == 0 || visibleSteps[position - 1].target != step.target) View.VISIBLE else View.GONE
            val cp = Regex("(?:^|&)cp([0-9]+)(?:&|$)", RegexOption.IGNORE_CASE).find(item.search)?.groupValues?.get(1)
            h.title.text = if (item.nickname.isBlank()) "低スコア検索式 ${item.sequence}"
                else "${item.ivs ?: OverlayPairParser.legacyIvLabel(item.search)}${cp?.let { "  CP$it" } ?: ""}"
            h.preview.text = item.search
            h.inputNick.visibility = if (item.nickname.isBlank()) View.GONE else View.VISIBLE
            h.title.contentDescription = "${item.speciesLabel} ${h.title.text}"
            for ((b, isSearch) in listOf(h.inputSearch to true, h.inputNick to false)) {
                val done = store.workflowVerified(item.id, isSearch)
                b.text = (if (done) "✓ " else "") + if (isSearch) "検索 自動入力" else "名前 自動入力"
                b.isEnabled = !controlsLocked
                b.background = background(if (done) Color.rgb(91, 115, 99) else Color.rgb(79, 96, 88))
                b.contentDescription = "${b.text}。タップで開始、長押しでチェック解除。名前は保存・一覧復帰の確認後に完了"
                b.setOnClickListener { requestGoInput(item, isSearch) }
                b.setOnLongClickListener {
                    if (!controlsLocked && done) {
                        store.setCopied(item.id, isSearch, false)
                        store.setWorkflowVerified(item.id, isSearch, false)
                        flow.markReview(item.id, isSearch, false)
                        store.item(item.id)?.let { itemsById[item.id] = it }
                        saveFlow(); refresh(reload = false)
                    }
                    true
                }
            }
            return root
        }
        private fun readerCleanupView(position: Int, recycled: View?): View {
            val root = (recycled as? LinearLayout)
                ?.takeIf { it.tag is ReaderCleanupHolder }
                ?: LinearLayout(app).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(4), 0, dp(6))
                    val heading = label("", 12f)
                    val preview = label("", 11f).apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(Color.rgb(200, 221, 207))
                    }
                    addView(heading, LinearLayout.LayoutParams(-1, -2))
                    addView(preview, LinearLayout.LayoutParams(-1, -2))
                    val controls = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
                    val input = button("検索 自動入力") {}
                    val copy = button("コピー") {}
                    controls.addView(input, LinearLayout.LayoutParams(0, dp(36), 1f)
                        .apply { marginEnd = dp(4) })
                    controls.addView(copy, LinearLayout.LayoutParams(dp(66), dp(36)))
                    addView(controls)
                    tag = ReaderCleanupHolder(heading, preview, input, copy)
                }
            val holder = root.tag as ReaderCleanupHolder
            val actualPosition = position + readerCursor.position
            val item = readerCleanupItems[actualPosition]
            val review = item.confidence == "unranked_review"
            holder.title.text = "非厳選 ${actualPosition + 1} / ${readerCleanupItems.size}" +
                if (review) " · 未評価・要確認（削除禁止）" else " · 評価済み"
            holder.preview.text = item.search
            holder.input.isEnabled = !controlsLocked
            holder.copy.isEnabled = !controlsLocked
            holder.input.contentDescription = "非厳選検索式 ${actualPosition + 1} をGO検索欄に入力"
            holder.copy.contentDescription = "非厳選検索式 ${actualPosition + 1} をコピー"
            holder.input.setOnClickListener { inputCleanup(item.search) }
            holder.copy.setOnClickListener {
                try {
                    com.example.pokemonivreader.ClipboardCopy.write(app, "非厳選検索式", GoHoldSearch.exclude(item.search))
                    if (review) toast("要確認候補。博士送りはしないでください")
                } catch (_: Exception) {
                    toast("検索式をコピーできませんでした")
                }
            }
            return root
        }
        private fun cleanupView(position: Int, recycled: View?): View {
            val root = (recycled as? LinearLayout)?.takeIf { it.tag is CleanupHolder } ?: LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6))
                val title = label("", 12f); addView(title)
                val action = button("") {}.apply { maxLines = 3; minimumHeight = dp(44) }
                addView(action, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                val manual = button("手動で博士送り（sendなし）") {}
                addView(manual, LinearLayout.LayoutParams(-1, dp(36)).apply { topMargin = dp(4) })
                tag = CleanupHolder(title, action, manual)
            }
            val h = root.tag as CleanupHolder
            val step = visibleSteps[position]
            val heading = if (position == 0 || visibleSteps[position - 1].target != step.target) step.target + "\n" else ""
            if (step.prevSources.isNotEmpty()) {
                val i = cursor.position + position
                val names = step.prevSources.map { it.substringBefore('&') }.distinct().joinToString("・")
                val tagName = SelectionTagPolicy.tagForTarget(step.target)
                h.title.text = heading + "重要個体の検索前に$tagName を解除"
                h.action.visibility = View.VISIBLE
                h.action.text = (if (prevGroupDone(i, step)) "✓ " else "") + "${tagName}解除 · $names"
                h.action.contentDescription = "${step.target}の前回タグを安全に解除"
                h.action.setOnClickListener { startPrevGroup(i, directlyStarted = true) }
                h.manual.visibility = View.GONE
            } else {
                val expression = step.expression
                val scopedQuery = expression?.let(::namedCleanupQuery)
                val display = scopedQuery ?: expression.orEmpty()
                val cleanupNote = if (expression != null && scopedQuery == null)
                    "地方フォーム・種名を確認できないため見送り"
                else "整理用 · prev / subprev / curr を保護（自動操作は図鑑番号で限定）"
                h.title.text = heading + OverlayQueuePresentation.annotation(step, itemsById)
                    .takeIf { it.isNotBlank() }?.let { "$it\n" }.orEmpty() +
                    cleanupNote
                h.action.visibility = if (expression == null) View.GONE else View.VISIBLE
                h.action.text = (if (manualHandoffs) "検索入力 · " else "自動整理 · ") + display
                h.action.contentDescription = "$display の整理"
                h.action.setOnClickListener { requestCommand(step, cursor.position + position, directlyStarted = true) }
                h.manual.visibility = if (expression == null) View.GONE else View.VISIBLE
                h.manual.text = "手動で博士送り（sendタグなし）"
                h.manual.contentDescription = "$display を検索して手動で博士送り。タグは付与しない"
                h.manual.setOnClickListener { expression?.let(::manualTransferSearch) }
            }
            h.action.isEnabled = !controlsLocked
            h.manual.isEnabled = !controlsLocked
            return root
        }
    }
}


