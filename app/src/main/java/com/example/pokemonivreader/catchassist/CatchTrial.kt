package com.example.pokemonivreader.catchassist

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One active trial. A gesture may follow only a frame verified for its current state. */
internal class CatchTrial(
    private val main: Handler,
    private val worker: Executor,
    private val profile: CatchProfile,
    private val ledger: CatchAttemptLedger,
    private val vision: CatchGameVision,
    private val port: Port,
    private val finished: (String) -> Unit,
) {
    data class Frame(
        val image: Bitmap,
        val bounds: Rect,
        val windowId: Int,
        val at: Long = SystemClock.elapsedRealtime(),
    ) {
        fun point(x: Float, y: Float) = PointF(bounds.left + x * bounds.width(), bounds.top + y * bounds.height())
    }

    sealed interface Capture {
        data class Success(val frame: Frame) : Capture
        data class Failure(val reason: String, val retryable: Boolean = true) : Capture
    }

    data class Stroke(
        val path: Path,
        val startMs: Long,
        val durationMs: Long,
        val finger: Int = 0,
        val continueAt: PointF? = null,
    )
    data class TitleLine(val text: String, val bounds: Rect?, val words: List<Rect>)
    private data class Reading(val name: String, val stamp: CatchTitleStamp?)

    interface Port {
        fun foreground(): Boolean
        fun sameWindow(frame: Frame): Boolean
        fun current(frame: Frame): Boolean
        fun capture(result: (Capture) -> Unit)
        fun readName(frame: Bitmap, result: (List<TitleLine>?) -> Unit)
        fun gesture(frame: Frame, strokes: List<Stroke>, continuation: List<Stroke>?, result: (Boolean) -> Unit)
    }

    private enum class Step(val timeout: Long) {
        START(8_000), MAP(16_000), ENCOUNTER(7_000), VERIFIED(4_000),
        MENU(5_000), READY(5_000), QUICK_EXIT(8_000), DETAIL(8_000), RETURN(7_000),
    }

    private var step = Step.START
    private var deadline = 0L
    private var running = true
    private var encounterReading: Reading? = null
    private var waitReason: String? = null
    private var captureFailures = 0
    private var ball: CatchBall? = null
    private data class MapCandidate(val spawn: CatchGameVision.Box, val frameAt: Long)
    private data class RecentAttempt(val spawn: CatchGameVision.Box, val at: Long)
    private var candidate: MapCandidate? = null

    private val recentAttempts = ArrayDeque<RecentAttempt>()
    private var dismissedBerryTray = false
    private var unresolved = false
    private val tick = Runnable { advance() }
    private val watchdog = Runnable { stop("画面取得または操作の応答がありません") }

    fun start() = waitFor(Step.START, 300L)

    fun stop(message: String) {
        if (!running) return
        running = false
        main.removeCallbacks(tick)
        main.removeCallbacks(watchdog)
        val detail = "${step.label()}／$message"
        finished(if (unresolved) "$detail。投球結果はゲームで確認してください" else detail)
    }

    private fun waitFor(next: Step, delay: Long = 450L) {
        step = next
        waitReason = null
        captureFailures = 0
        deadline = SystemClock.elapsedRealtime() + next.timeout
        if (running) main.postDelayed(tick, delay)
    }

    private fun poll(reason: String? = null) {
        if (reason != null) waitReason = reason
        if (running) main.postDelayed(tick, 420L)
    }

    private fun Step.label(): String = when (this) {
        Step.START -> "開始"
        Step.MAP -> "マップ候補"
        Step.ENCOUNTER -> "遭遇確認"
        Step.VERIFIED -> "ボール選択ボタン"
        Step.MENU -> "ボール一覧"
        Step.READY -> "投球前確認"
        Step.QUICK_EXIT -> "投球後"
        Step.DETAIL -> "捕獲詳細"
        Step.RETURN -> "マップへ戻る"
    }

    private fun advance() {
        if (!running) return
        if (SystemClock.elapsedRealtime() >= deadline) {
            stop(waitReason ?: when (step) {
                Step.START -> "開始できませんでした。Pokémon GOのマップか捕獲画面を開いてください"
                Step.MAP -> "登録したポケモンを見つけられませんでした"
                Step.READY -> "指定ボールか、きのみボタンの見本が一致せず停止しました"
                Step.QUICK_EXIT -> "投球後に逃げるボタンを確認できず停止しました"
                else -> "次の画面を確認できないため停止しました"
            })
            return
        }
        if (!port.foreground()) {
            if (step == Step.START) poll() else stop("Pokémon GO が前面にないため停止しました")
            return
        }
        if (step == Step.START || step == Step.MAP) {
            val allowance = ledger.allowance(System.currentTimeMillis(), profile.caps)
            if (allowance.remaining == 0) {
                stop(if (allowance.clockRegressed) "端末時刻が戻ったため停止しました" else "投球の設定上限に達しました")
                return
            }
        }
        main.postDelayed(watchdog, 4_000L)
        port.capture { capture ->
            main.removeCallbacks(watchdog)
            when (capture) {
                is Capture.Success -> {
                    if (!running) { capture.frame.image.recycle(); return@capture }
                    captureFailures = 0
                    inspect(capture.frame)
                }
                is Capture.Failure -> if (running) {
                    if (capture.retryable && ++captureFailures < 3) poll(capture.reason) else stop(capture.reason)
                }
            }
        }
    }

    private fun inspect(frame: Frame) {
        val state = step
        if (!port.current(frame) || frame.image.width >= frame.image.height) {
            frame.image.recycle()
            stop("画面の位置が変わったため停止しました")
            return
        }
        val encounter = markerMatches(frame, CatchMarker.ENCOUNTER)
        val mapByExample = !encounter && markerMatches(frame, CatchMarker.MAP)
        val needsMap = state in MAP_STEPS && !encounter && !mapByExample &&
            !markerMatches(frame, CatchMarker.BAG_OPEN) && !markerMatches(frame, CatchMarker.BERRY_TRAY) &&
            !markerMatches(frame, CatchMarker.RESULT_OK) && !markerMatches(frame, CatchMarker.DETAIL_EXIT)
        if (!needsMap) {
            inspectScene(frame, state, encounter, mapByExample)
            return
        }

        main.postDelayed(watchdog, 4_000L)
        worker.execute {
            val map = runCatching { vision.isMap(frame.image) }
            main.post {
                main.removeCallbacks(watchdog)
                if (!running) {
                    frame.image.recycle()
                    return@post
                }
                if (!port.current(frame)) {
                    frame.image.recycle()
                    poll("マップ確認中にゲーム画面が変わりました")
                    return@post
                }
                if (map.isFailure && (state == Step.START || state == Step.MAP)) {
                    frame.image.recycle()
                    poll("マップ認識を実行できません。マップメニューの見本を追加してください")
                    return@post
                }
                inspectScene(frame, state, encounter, map.getOrDefault(false))
            }
        }
    }

    private fun inspectScene(frame: Frame, state: Step, encounter: Boolean, map: Boolean) {
        if (state in ENCOUNTER_ENTRY && encounter) {
            candidate = null
            val bag = markerMatches(frame, CatchMarker.BAG_OPEN)
            val berries = markerMatches(frame, CatchMarker.BERRY_TRAY)
            when {
                !bag && !berries -> readEncounter(frame)
                bag && !berries && state == Step.START && profile.rules.isEmpty() -> {
                    try {
                        ball = profile.defaultBall
                        chooseBall(frame)
                    } finally { frame.image.recycle() }
                }
                !bag && berries && state == Step.START -> {
                    try { dispatch(frame, listOf(tap(frame.point(.50f, .48f))), Step.ENCOUNTER) }
                    finally { frame.image.recycle() }
                }
                else -> {
                    frame.image.recycle()
                    poll("遭遇画面の道具一覧を閉じてください")
                }
            }
            return
        }
        if (state == Step.READY) {
            verifyBallAndThrow(frame)
            return
        }
        if (state == Step.START || state == Step.MAP) {
            if (!map) {
                candidate = null
                frame.image.recycle()
                poll(if (encounter) "遭遇画面の道具一覧を閉じてください"
                    else "ポケモンをタップする前にマップを確認できませんでした")
                return
            }
            if (state == Step.START) {
                frame.image.recycle()
                waitFor(Step.MAP, 0L)
                return
            }
            inspectMap(frame)
            return
        }
        try {
            when (state) {
                Step.ENCOUNTER -> if (map) returnToMap()
                    else poll("候補をタップしましたが、遭遇画面の逃げるボタンを確認できません")
                Step.VERIFIED -> {
                    val requested = ball
                    when {
                        !encounter -> poll("逃げるボタンが見本と一致しません")
                        requested != null && markerMatches(frame, CatchMarker.ready(requested)) ->
                            waitFor(Step.READY, 0L)
                        markerMatches(frame, CatchMarker.BAG) -> tapMarker(frame, CatchMarker.BAG, Step.MENU)
                        else -> poll("右下のボール選択ボタンが見本と一致しません")
                    }
                }
                Step.MENU -> chooseBall(frame)
                Step.QUICK_EXIT -> inspectQuickExit(frame, map)
                Step.DETAIL -> inspectDetail(frame, map)
                Step.RETURN -> if (map) returnToMap() else poll("操作後にマップを確認できません")
                Step.START, Step.MAP, Step.READY -> error("Frame is handled above")
            }
        } finally {
            frame.image.recycle()
        }
    }

    private fun inspectMap(frame: Frame) {
        val now = SystemClock.elapsedRealtime()
        while (recentAttempts.firstOrNull()?.let { now - it.at >= 8_000L } == true) {
            recentAttempts.removeFirst()
        }
        val recent = recentAttempts.toList()
        val previous = candidate?.takeIf { frame.at > it.frameAt && frame.at - it.frameAt <= 2_000L }
        main.postDelayed(watchdog, 4_000L)
        worker.execute {
            val detection = runCatching {
                val objects = vision.mapObjects(frame.image)
                val excluded = if (objects.wild.isEmpty() || profile.excludedMapExamples.isEmpty()) emptyList()
                else AndroidCatchVision.find(frame.image, profile.excludedMapExamples.map {
                    CatchVisionMatcher.Reference("対象外", it)
                })
                val eligible = objects.eligible(excluded).filter { spawn ->
                    recent.none { CatchMapObjects.sameSpawn(it.spawn, spawn) }
                }
                if (previous == null) chooseSpawn(frame, eligible)
                else eligible.firstOrNull { CatchMapObjects.sameSpawn(previous.spawn, it) }
            }
            frame.image.recycle()
            main.post {
                main.removeCallbacks(watchdog)
                if (!running) return@post
                if (detection.isFailure) { stop("マップの検出モデルを実行できませんでした"); return@post }
                val next = detection.getOrNull()
                if (next == null || !port.current(frame)) {
                    candidate = null
                    poll(if (next == null) "マップ上で連続して確認できる野生ポケモンが見つかりません"
                        else "候補の検出中に画面が変わりました")
                    return@post
                }
                if (previous == null) {
                    candidate = MapCandidate(next, frame.at)
                    poll()
                    return@post
                }
                candidate = null
                if (recentAttempts.size == 32) recentAttempts.removeFirst()
                recentAttempts.addLast(RecentAttempt(next, SystemClock.elapsedRealtime()))
                encounterReading = null
                ball = null
                dispatch(frame, listOf(tap(frame.point(next.x, next.y))), Step.ENCOUNTER, 1_350L)
            }
        }
    }

    private fun chooseSpawn(frame: Frame, candidates: List<CatchGameVision.Box>): CatchGameVision.Box? {
        if (candidates.isEmpty()) return null
        fun hasExample(candidate: CatchGameVision.Box, example: CatchVisionMatcher.Detection): Boolean =
            abs(example.centerX - candidate.x) <= candidate.width / 2 &&
                abs(example.centerY - candidate.y) <= candidate.height / 2
        val species = AndroidCatchVision.find(frame.image, profile.references)
        for (rule in profile.rules) {
            candidates.firstOrNull { box -> species.any { it.species == rule.speciesId && hasExample(box, it) } }
                ?.let { return it }
        }
        val examples = AndroidCatchVision.find(frame.image, profile.wildExamples.map {
            CatchVisionMatcher.Reference("野生候補", it)
        })
        return candidates.firstOrNull { box -> examples.any { hasExample(box, it) } } ?: candidates.first()
    }

    private fun readEncounter(frame: Frame) {
        if (profile.rules.isEmpty()) {
            // With one configured default ball the name cannot change the decision.
            try {
                ball = profile.defaultBall
                if (ball != null) waitFor(Step.VERIFIED, 0L)
                else stop("使用するボールが設定されていません")
            } finally {
                frame.image.recycle()
            }
            return
        }
        readName(frame) { verified, reading ->
            try {
                if (reading == null) {
                    poll("遭遇画面の名前と CP を読み取れません")
                    return@readName
                }
                val selected = profile.rules.firstOrNull { canonicalName(it.speciesId) == reading.name }?.ball
                    ?: profile.defaultBall
                if (selected == null) {
                    tapMarker(verified, CatchMarker.ENCOUNTER, Step.RETURN)
                    return@readName
                }
                encounterReading = reading
                ball = selected
                waitFor(Step.VERIFIED, 0L)
            } finally {
                verified.image.recycle()
            }
        }
    }

    /** Transfers the accepted frame to the callback, which must recycle it. */
    private fun readName(frame: Frame, result: (Frame, Reading?) -> Unit) {
        main.postDelayed(watchdog, 4_000L)
        port.readName(frame.image) { lines ->
            main.removeCallbacks(watchdog)
            if (!running) {
                frame.image.recycle()
                return@readName
            }
            val text = lines.orEmpty()
            val title = encounterTitle(text.map { it.text })
            val reading = title?.let {
                val bounds = it.lineIndices.flatMap { index ->
                    val line = text[index]
                    line.words.ifEmpty { listOf(line.bounds) }
                }
                Reading(it.name, CatchTitleStamp.from(frame.image, bounds))
            }
            withFreshTitle(frame, reading?.stamp) { verified -> result(verified, reading) }
        }
    }

    /** A slow read does not make its old coordinates safe. Validate the title on a fresh frame. */
    private fun withFreshTitle(
        frame: Frame, stamp: CatchTitleStamp?, requireTitle: Boolean = true, use: (Frame) -> Unit,
    ) {
        if (port.current(frame)) {
            use(frame)
            return
        }
        frame.image.recycle()
        if (!port.sameWindow(frame)) {
            stop("Pokémon GOの表示先が変わったため、操作を中断しました")
            return
        }
        if (stamp == null && requireTitle) {
            poll("読み取りに時間がかかり、名前とCPの表示を照合できませんでした")
            return
        }
        main.postDelayed(watchdog, 4_000L)
        port.capture { result ->
            main.removeCallbacks(watchdog)
            when (result) {
                is Capture.Success -> {
                    val fresh = result.frame
                    if (running && fresh.windowId == frame.windowId && fresh.bounds == frame.bounds &&
                        port.current(fresh) && (!requireTitle || stamp?.matches(fresh.image) == true) &&
                        markerMatches(fresh, CatchMarker.ENCOUNTER)) {
                        use(fresh)
                    } else {
                        fresh.image.recycle()
                        if (running) poll("再取得した画面で同じ名前とCPを確認できません")
                    }
                }
                is Capture.Failure -> if (running) {
                    if (result.retryable) poll(result.reason) else stop(result.reason)
                }
            }
        }
    }

    private fun chooseBall(frame: Frame) {
        val requested = ball ?: return stop("使用するボールが設定されていません")
        val option = CatchMarker.option(requested)
        if (!markerMatches(frame, CatchMarker.BAG_OPEN)) { poll("ボール一覧の見出しが見本と一致しません"); return }
        if (!markerMatches(frame, option)) {
            stop("選んだボールが見つかりません。在庫と画面見本を確認してください")
            return
        }
        tapMarker(frame, option, Step.READY)
    }

    private fun verifyBallAndThrow(frame: Frame) {
        if (throwControls(frame) == null) {
            frame.image.recycle()
            return
        }
        val confirmed = encounterReading
        if (profile.rules.isEmpty() || confirmed?.stamp?.matches(frame.image) == true) {
            planThrow(frame, confirmed)
            return
        }
        readName(frame) { verified, reading ->
            if (reading == null || reading.name != confirmed?.name) {
                verified.image.recycle()
                poll("投球直前の名前が一致しません")
            } else {
                planThrow(verified, reading)
            }
        }
    }

    private fun throwControls(frame: Frame): Pair<CatchAnchor, CatchAnchor>? {
        val requested = ball
        val anchor = requested?.let { findMarker(frame, CatchMarker.ready(it)) }
        val berry = findMarker(frame, CatchMarker.BERRY)
        val failed = when {
            !markerMatches(frame, CatchMarker.ENCOUNTER) -> "逃げるボタンが見本と一致しません"
            markerMatches(frame, CatchMarker.BAG_OPEN) || markerMatches(frame, CatchMarker.BERRY_TRAY) ->
                "道具の一覧が閉じるのを待っています"
            berry == null -> "左下のきのみボタンが見本と一致しません"
            anchor == null -> "手前の投球用ボールが見本と一致しません"
            CatchBall.entries.any { it != requested && markerMatches(frame, CatchMarker.ready(it)) } ->
                "手前のボールが複数の見本に一致して種類を確定できません"
            else -> null
        }
        if (failed != null || anchor == null || berry == null) {
            poll(failed)
            return null
        }
        return anchor to berry
    }

    private fun planThrow(frame: Frame, reading: Reading?, refreshedAim: Boolean = false) {
        if (profile.throwExamples.size == 1 && CatchThrowRange.ANY in profile.throwExamples) {
            useThrowFrame(frame, null)
            return
        }
        main.postDelayed(watchdog, 4_000L)
        worker.execute {
            val aim = runCatching { vision.encounter(frame.image)?.let(CatchThrowPlan::aim) }.getOrNull()
            main.post {
                main.removeCallbacks(watchdog)
                if (!running) {
                    frame.image.recycle()
                    return@post
                }
                withFreshTitle(frame, reading?.stamp, requireTitle = profile.rules.isNotEmpty()) { verified ->
                    if (verified !== frame && !refreshedAim) {
                        planThrow(verified, reading, refreshedAim = true)
                    } else {
                        useThrowFrame(verified, aim.takeIf { verified === frame })
                    }
                }
            }
        }
    }

    private fun useThrowFrame(frame: Frame, aim: CatchThrowPlan.Aim?) {
        try {
            val (anchor, berry) = throwControls(frame) ?: return
            val start = frame.point(anchor.x, anchor.y)
            val berryPoint = frame.point(berry.x, berry.y)
            val sample = profile.throwExample(aim?.range)
            if (sample != null) {
                val aligned = sample.alignedTo(anchor.x, anchor.y, anchor.aspect)
                    ?: return stop("手前のボール位置が投球に使える範囲の外です")
                playLearnedThrow(frame, berryPoint, sample, aligned)
                return
            }
            val path = curve(frame.bounds, start, aim) ?: return stop("投球位置が許容範囲の外です")
            val duration = aim?.durationMs ?: CatchQuickPolicy.BALL_DURATION_MS
            val hold = Path().apply { moveTo(berryPoint.x, berryPoint.y) }
            val strokes = listOf(
                Stroke(hold, 0L, CatchQuickPolicy.BALL_START_MS + duration + CatchQuickPolicy.AFTER_THROW_MS),
                Stroke(path, CatchQuickPolicy.BALL_START_MS, duration, finger = 1),
            )
            throwBall(frame, strokes)
        } finally {
            frame.image.recycle()
        }
    }

    private fun playLearnedThrow(
        frame: Frame, berry: PointF, sample: CatchThrowExample, points: List<CatchThrowPoint>,
    ) {
        fun ballPath(from: Int, to: Int) = Path().apply {
            for (index in from..to) {
                val point = frame.point(points[index].x, points[index].y)
                if (index == from) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
        }
        fun hold() = Path().apply { moveTo(berry.x, berry.y) }

        val launch = sample.launchIndex()
        if (launch == null) {
            throwBall(frame, listOf(
                Stroke(hold(), 0L, CatchQuickPolicy.BALL_START_MS + sample.durationMs + CatchQuickPolicy.AFTER_THROW_MS),
                Stroke(ballPath(0, points.lastIndex), CatchQuickPolicy.BALL_START_MS, sample.durationMs, finger = 1),
            ))
            return
        }

        val spinMs = points[launch].atMs
        val flickMs = sample.durationMs - spinMs
        val launchPoint = frame.point(points[launch].x, points[launch].y)
        val spin = listOf(
            Stroke(hold(), 0L, CatchQuickPolicy.BALL_START_MS + spinMs, continueAt = berry),
            Stroke(ballPath(0, launch), CatchQuickPolicy.BALL_START_MS, spinMs, finger = 1, continueAt = launchPoint),
        )
        val flick = listOf(
            Stroke(hold(), 0L, flickMs + CatchQuickPolicy.AFTER_THROW_MS),
            Stroke(ballPath(launch, points.lastIndex), 0L, flickMs, finger = 1),
        )
        throwBall(frame, spin, flick)
    }

    private fun throwBall(frame: Frame, strokes: List<Stroke>, continuation: List<Stroke>? = null) {
        if (!port.current(frame)) { stop("投球前に画面が変わりました"); return }
        when (ledger.reserve(System.currentTimeMillis(), profile.caps)) {
            is CatchReservation.AtLimit -> stop("投球の設定上限に達しました")
            CatchReservation.ClockRegressed -> stop("端末時刻が戻ったため停止しました")
            CatchReservation.StorageFailure -> stop("投球回数を保存できないため停止しました")
            is CatchReservation.Reserved -> {
                unresolved = true
                dismissedBerryTray = false
                dispatch(frame, strokes, Step.QUICK_EXIT, 280L, continuation)
            }
        }
    }

    private fun inspectQuickExit(frame: Frame, map: Boolean) {
        val next = CatchQuickPolicy.next(
            map = map,
            reward = markerMatches(frame, CatchMarker.RESULT_OK) && CatchMarker.DETAIL_EXIT in profile.anchors,
            berries = markerMatches(frame, CatchMarker.BERRY_TRAY),
            flee = markerMatches(frame, CatchMarker.ENCOUNTER),
            ballReady = ball?.let { markerMatches(frame, CatchMarker.ready(it)) } == true,
            ballMenu = markerMatches(frame, CatchMarker.BAG_OPEN),
        )
        when (next) {
            CatchQuickPolicy.Next.MAP -> returnToMap()
            CatchQuickPolicy.Next.REWARD -> tapMarker(frame, CatchMarker.RESULT_OK, Step.DETAIL)
            CatchQuickPolicy.Next.DISMISS_BERRIES -> {
                if (dismissedBerryTray) {
                    poll()
                    return
                }
                dismissedBerryTray = true
                // This point is above the verified tray and away from the encounter buttons.
                dispatch(frame, listOf(tap(frame.point(.50f, .48f))), Step.QUICK_EXIT)
            }
            CatchQuickPolicy.Next.FLEE -> tapMarker(frame, CatchMarker.ENCOUNTER, Step.RETURN)
            CatchQuickPolicy.Next.WAIT -> poll()
        }
    }

    private fun returnToMap() {
        // Quick catch confirms only that the encounter closed.
        unresolved = false
        waitFor(Step.MAP)
    }

    private fun inspectDetail(frame: Frame, map: Boolean) {
        if (markerMatches(frame, CatchMarker.DETAIL_EXIT)) {
            unresolved = false
            tapMarker(frame, CatchMarker.DETAIL_EXIT, Step.RETURN)
        } else if (map) {
            unresolved = false
            waitFor(Step.MAP)
        } else {
            poll()
        }
    }

    private fun findMarker(frame: Frame, marker: CatchMarker): CatchAnchor? =
        profile.markerExamples(marker).firstNotNullOfOrNull { matchMarker(frame, it, marker == CatchMarker.ENCOUNTER) }

    private fun matchMarker(frame: Frame, original: CatchAnchor, whiteIcon: Boolean): CatchAnchor? {
        if (original.matches(frame.image, whiteIcon)) return original
        // Imported device screenshots may include a status/navigation bar outside the GO window.
        val fullHeight = frame.bounds.width() / original.aspect
        if (frame.bounds.left != 0 || fullHeight <= frame.bounds.height() ||
            fullHeight - frame.bounds.height() > fullHeight * .12f || frame.bounds.bottom > fullHeight + 2f) return null
        val y = (original.y * fullHeight - frame.bounds.top) / frame.bounds.height()
        if (y !in 0f..1f) return null
        val aspect = frame.image.width.toFloat() / frame.image.height
        if (aspect !in .3f..1f) return null
        val adjusted = original.copy(y = y, aspect = aspect)
        return adjusted.takeIf { it.matches(frame.image, whiteIcon) }
    }

    private fun markerMatches(frame: Frame, marker: CatchMarker) = findMarker(frame, marker) != null

    private fun tapMarker(frame: Frame, marker: CatchMarker, next: Step) {
        val anchor = findMarker(frame, marker) ?: return stop("「${marker.label}」の位置を確認できませんでした")
        val point = frame.point(anchor.x, anchor.y)
        dispatch(frame, listOf(tap(point)), next)
    }

    private fun tap(point: PointF) = Stroke(Path().apply { moveTo(point.x, point.y) }, 0L, 55L)

    private fun dispatch(
        frame: Frame, strokes: List<Stroke>, next: Step, delay: Long = 450L,
        continuation: List<Stroke>? = null,
    ) {
        if (!running) return
        if (!port.current(frame)) { stop("操作前にゲーム画面が変わりました"); return }
        val activeMs = strokes.maxOf { it.startMs + it.durationMs } +
            (continuation?.maxOf { it.startMs + it.durationMs } ?: 0L)
        main.postDelayed(watchdog, maxOf(3_000L, activeMs + 900L))
        port.gesture(frame, strokes, continuation) { ok ->
            main.removeCallbacks(watchdog)
            if (!running) return@gesture
            if (ok) waitFor(next, delay) else stop("ジェスチャーを完了できませんでした")
        }
    }

    companion object {
        private val ENCOUNTER_ENTRY = setOf(Step.START, Step.MAP, Step.ENCOUNTER)
        private val MAP_STEPS = setOf(Step.START, Step.MAP, Step.ENCOUNTER, Step.QUICK_EXIT, Step.DETAIL, Step.RETURN)
        private val cpSuffix = Regex("""(?i)\s*[/／|]?\s*CP\s*[\d?？]*\s*$""")

        internal fun canonicalName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(cpSuffix, "").trim().lowercase(Locale.ROOT)

        private val cpLine = Regex("""(?i)CP\s*[\d?？]+""")
        private val nameText = Regex("""[\p{L}][\p{L}\p{M}\p{N} .'’♀♂-]{0,39}""")

        internal data class EncounterTitle(val name: String, val lineIndices: List<Int>)

        internal fun encounterName(lines: List<String>): String? = encounterTitle(lines)?.name

        internal fun encounterTitle(lines: List<String>): EncounterTitle? {
            val text = lines.map { Normalizer.normalize(it, Normalizer.Form.NFKC).trim() }
            for ((index, line) in text.withIndex()) {
                val cp = cpLine.find(line) ?: continue
                val prefix = line.take(cp.range.first).trim(' ', '/', '／', '|', '○', '◯', '●')
                val name = canonicalName(prefix.ifBlank { text.getOrNull(index - 1).orEmpty() })
                if (nameText.matches(name)) {
                    val indices = if (prefix.isNotBlank()) listOf(index) else listOf(index - 1, index)
                    return EncounterTitle(name, indices)
                }
            }
            return null
        }

        internal fun curve(bounds: Rect, start: PointF, aim: CatchThrowPlan.Aim? = null): Path? {
            val width = bounds.width()
            val height = bounds.height()
            if (width <= 0 || height <= width ||
                start.x !in (bounds.left + width * .3f)..(bounds.left + width * .7f) ||
                start.y !in (bounds.top + height * .67f)..(bounds.top + height * .96f)) return null
            val radius = minOf(width * .055f, (bounds.bottom - start.y) * .4f)
            val centerX = start.x - radius
            val sweep = Path().apply { moveTo(start.x, start.y) }
            for (index in 1..30) {
                val angle = 2.0 * PI * index / 24
                sweep.lineTo(centerX + radius * cos(angle).toFloat(), start.y + radius * sin(angle).toFloat())
            }
            sweep.cubicTo(
                bounds.left + width * .23f, bounds.top + height * .74f,
                bounds.left + width * .20f, bounds.top + height * .53f,
                bounds.left + width * (aim?.x ?: .54f), bounds.top + height * (aim?.releaseY ?: .43f),
            )
            return sweep
        }
    }
}
