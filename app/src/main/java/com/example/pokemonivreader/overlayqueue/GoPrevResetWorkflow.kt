package com.example.pokemonivreader.overlayqueue

import android.os.Handler
import android.os.SystemClock

/** Selection counts and checkbox readback prove bulk removal; no transfer action is exposed. */
internal class GoPrevResetWorkflow(handler:Handler,private val query:String,private val port:Port,
    private val done:(Boolean,String)->Unit) {
    interface Port {
        fun foreground():Boolean
        fun requestForeground()
        fun enter(query:String,result:(Boolean,String)->Unit)
        fun observe(result:(GoWorkflowPolicy.Scene?)->Unit)
        fun tap(box:GoWorkflowPolicy.Box):Boolean
        fun hold(box:GoWorkflowPolicy.Box):Boolean
        fun submitSearch() {}
        fun hideKeyboard() {}
        fun resolveKeyboard(query: String): CleanupKeyboardStatus? = null
        fun searchStillActive(scene: GoWorkflowPolicy.Scene, query: String): Boolean =
            GoWorkflowPolicy.showsPrevSearch(scene, query)
        fun exitSelection(scene:GoWorkflowPolicy.Scene):Boolean = false
        fun fallbackFirstCard(scene: GoWorkflowPolicy.Scene): GoWorkflowPolicy.Box? = null
        fun hint(message:String)
        fun stopped(stage: String) {}
        fun stopped(stage: String, reason: String, learningTarget: GoLearningTarget?) { stopped(stage) }
    }
    val tagName: String = requireNotNull(SelectionTagPolicy.tagInQuery(query)) { "保護タグが不正です" }
    private val pump=GoInputPump(handler,::poll)
    private val clock=GoActiveClock()
    private var deadline=0L
    private var waitingForForegroundSince: Long? = null
    private var closed=false
    private var busy=false
    private var stage=0
    val diagnosticStage: String get() = diagnosticStages[stage]

    companion object {
        private const val OBSERVATION_INTERVAL_MS = 260L
        val diagnosticStages = listOf(
            "PREV_INPUT", "PREV_RESULTS", "PREV_SELECT", "PREV_OPEN_TAGS",
            "PREV_FIND_TAG", "PREV_VERIFY_TAG", "PREV_RETURN", "PREV_RECHECK",
        )
    }
    private var epoch=0
    private var total:Int?=null
    private var previous:GoWorkflowPolicy.Scene?=null
    private var lastObservationAt:Long?=null
    private var emptySince:Long?=null
    private var selectionExitSent=false
    private var heldFrom: GoWorkflowPolicy.Scene? = null
    private var lastHoldAt = 0L
    private var holdAttempts = 0
    private var keyboardOpenSince: Long? = null
    private var lastKeyboardHideAt: Long? = null
    private var keyboardHideAttempts = 0
    private var keyboardHideRequested = false
    private var lostQueryRetries = 0
    private var stage7Retried = false
    private var lostFilterSince: Long? = null
    private var unverifiedQuerySince: Long? = null
    fun needsTagRow() = stage == 4 || stage == 5
    fun needsBulkTag() = stage == 2 || stage == 3
    fun needsSelectAll() = stage == 2
    fun needsGeometry()=stage==1 || stage==2 || stage==7
    fun needsSearchHeader(scene: GoWorkflowPolicy.Scene) =
        needsGeometry() && scene.selectAll == null && scene.bulkTag == null && !scene.searchSuggestions &&
            !GoWorkflowPolicy.showsPrevSearch(scene, query)
    private fun resetSearchWatchers() {
        previous = null
        emptySince = null
        keyboardOpenSince = null
        lastKeyboardHideAt = null
        keyboardHideAttempts = 0
        keyboardHideRequested = false
        lostFilterSince = null
        unverifiedQuerySince = null
    }
    fun start(){deadline=clock.now()+60_000;stage=0;resetSearchWatchers();pump.wake()}
    fun wake(){if(!closed){if(!port.foreground()){epoch++;previous=null;clock.pause()};pump.wake()}}
    fun cancel()=finish(false,"prev一括解除を停止しました")
    private fun finish(ok: Boolean, message: String, learningTarget: GoLearningTarget? = null) {
        if (closed) return
        closed = true
        pump.close()
        if (!ok) port.stopped(diagnosticStage, message, learningTarget)
        done(ok, message)
    }
    private fun poll(){
        if(closed)return
        if(!port.foreground()){
            val now = SystemClock.elapsedRealtime()
            val since = waitingForForegroundSince ?: now.also { waitingForForegroundSince = it }
            if (now-since >= 20_000L) {
                finish(false, "Pokémon GOが前面と認識されません。GOのポケモン一覧へ戻り、Androidの「厳選テキスト入力」を確認してください")
                return
            }
            epoch++;clock.pause();previous=null;port.requestForeground();pump.schedule(250);return
        }
        waitingForForegroundSince=null
        clock.resume()
        if(clock.now()>=deadline){finish(false,"prev一括解除を確認できませんでした。表示と選択件数を確認してください");return}
        if(busy){pump.schedule(120);return}
        if(stage==0){
            busy=true
            port.enter(query){ok,message->
                busy=false
                if(!closed){
                    if(!ok)finish(false,message)
                    else{
                        resetSearchWatchers()
                        stage=1
                        port.hint("prev付きの検索結果を確認中")
                        pump.wake()
                    }
                }
            }
            return
        }
        val remaining=lastObservationAt?.let{OBSERVATION_INTERVAL_MS-(clock.now()-it)} ?: 0L
        if(remaining>0){pump.schedule(remaining);return}
        lastObservationAt=clock.now()
        busy=true
        val capturedEpoch=epoch
        pump.schedule(120)
        port.observe { s->
            busy=false
            if(closed)return@observe
            if(!port.foreground() || capturedEpoch!=epoch){previous=null;pump.schedule(250);return@observe}
            if (s != null && (stage == 1 || stage == 7)) {
                val keyboardStatus = port.resolveKeyboard(query)
                when (keyboardStatus) {
                    CleanupKeyboardStatus.Pending -> {
                        previous = null
                        emptySince = null
                        lostFilterSince = null
                        unverifiedQuerySince = null
                        port.hint(if (s.keyboard) "検索確定とキーボード終了を確認中" else "検索結果の適用を確認中")
                        pump.schedule(OBSERVATION_INTERVAL_MS)
                        return@observe
                    }
                    is CleanupKeyboardStatus.Stopped -> {
                        finish(false, keyboardStatus.reason, keyboardStatus.learningTarget)
                        return@observe
                    }
                    CleanupKeyboardStatus.Ready -> {
                        if (s.keyboard) {
                            previous = null
                            emptySince = null
                            port.hint("キーボードが閉じるのを待機中")
                            pump.schedule(OBSERVATION_INTERVAL_MS)
                            return@observe
                        }
                        keyboardOpenSince = null
                        keyboardHideAttempts = 0
                    }
                    null -> {
                        if (GoWorkflowPolicy.searchNeedsSubmit(s)) port.submitSearch()
                        // Search input has already been verified by enter(). When the
                        // filtered result list is visible, dismiss the IME *before*
                        // checking the possibly occluded search header or holding a card.
                        // Closing the IME alone cannot change tags; all later gestures
                        // still require proof of the exact search on a fresh frame.
                        if (s.keyboard && s.list && s.count != null && !s.searchSuggestions &&
                            !s.rename && !s.detail && !s.tagList) {
                            previous = null; emptySince = null
                            val now = clock.now()
                            val since = keyboardOpenSince ?: now.also { keyboardOpenSince = it }
                            if (now - since >= 9_000L) {
                                finish(false, "キーボードが閉じません。GOのキーボードを閉じてからprev解除を再開してください")
                                return@observe
                            }
                            if (keyboardHideAttempts < 2 &&
                                (lastKeyboardHideAt == null || now - lastKeyboardHideAt!! >= 1_500L)) {
                                lastKeyboardHideAt = now
                                keyboardHideAttempts++
                                keyboardHideRequested = true
                                port.hideKeyboard()
                            }
                            port.hint("検索結果は表示されています。キーボードを閉じて検索条件を再確認中")
                            pump.schedule(OBSERVATION_INTERVAL_MS)
                            return@observe
                        }
                        if (!s.keyboard) {
                            keyboardOpenSince = null
                            keyboardHideAttempts = 0
                        }
                    }
                }
                if (s.detail || s.rename || s.tagList) {
                    finish(false, "検索結果の確認中に別のGO画面へ切り替わったため、停止しました")
                    return@observe
                }
                if (!port.searchStillActive(s, query)) {
                    previous = null; emptySince = null
                    val now = clock.now()
                    val canRecover = if (stage == 1) lostQueryRetries == 0 else !stage7Retried
                    val lostCandidate = !s.keyboard && s.list && !s.searchSuggestions && s.count == null &&
                        (keyboardHideRequested || keyboardStatus == CleanupKeyboardStatus.Ready || s.cards.size >= 2)
                    if (lostCandidate) {
                        val lostSince = lostFilterSince ?: now.also { lostFilterSince = it }
                        if (canRecover && (keyboardHideRequested || now - lostSince >= 400L)) {
                            if (stage == 1) lostQueryRetries++ else stage7Retried = true
                            resetSearchWatchers()
                            port.hint("検索が解除された可能性があるため一度だけ再入力します")
                            busy = true
                            port.enter(query) { ok, message ->
                                busy = false
                                if (!closed) {
                                    if (!ok) finish(false, "prev検索の再入力に失敗しました: $message")
                                    else { lastObservationAt = null; pump.wake() }
                                }
                            }
                            return@observe
                        }
                        if (!canRecover && now - lostSince >= 1_200L) {
                            finish(false, "検索条件が消失し、再入力後も維持されなかったため、別の個体のタグを変更しないよう停止しました")
                            return@observe
                        }
                    } else {
                        lostFilterSince = null
                    }
                    val since = unverifiedQuerySince ?: now.also { unverifiedQuerySince = it }
                    if (now - since >= 12_000L) {
                        finish(false, "検索した種名と #${tagName} を確認できません。別の個体のタグを変更しないため停止しました")
                        return@observe
                    }
                    port.hint("検索欄に対象の名前と #${tagName} が表示されるのを確認中")
                    pump.schedule(OBSERVATION_INTERVAL_MS)
                    return@observe
                }
                lostFilterSince = null
                unverifiedQuerySince = null
                keyboardHideRequested = false
            }
            if(s!=null)when(stage){
                1->{
                    if(emptyConfirmed(s)){finish(true,"prev付きの対象は0件です");return@observe}
                    if(s.list && s.count!=null && s.count>0 && (s.cards.isNotEmpty() || port.fallbackFirstCard(s)!=null)){
                        val old=previous;previous=s
                        if(old!=null && GoWorkflowPolicy.sameResults(old,s,compareLabels=false)){
                            total=s.count
                            holdFirstResult(s)
                        }
                    }else previous=null
                }
                2 -> {
                    if (s.selectedCount == total && s.bulkTag != null) stage = 3
                    else if (s.selectAll != null && port.tap(s.selectAll)) stage = 3
                    else if (s.selectedCount == null && s.bulkTag == null && s.selectAll == null &&
                        clock.now() - lastHoldAt >= 1_200L &&
                        heldFrom?.let { GoWorkflowPolicy.sameResults(it, s) } == true &&
                        port.searchStillActive(s, query)) holdFirstResult(s)
                }
                3->{
                    if(s.selectedCount==total && s.bulkTag!=null && port.tap(s.bulkTag))stage=4
                    else port.hint("SELECT ALL後の全${total}体の選択を照合中")
                }
                4->{if(s.tagList && s.prev!=null && s.prevSelected!=null){
                    if(s.prevSelected){if(port.tap(s.prev))stage=5}else confirmDone(s)
                }}
                5->{if(s.tagList && s.prevSelected==false)confirmDone(s)}
                6->{
                    if(s.selectAll!=null || s.bulkTag!=null) {
                        if (s.keyboard) port.hideKeyboard()
                        else if (!selectionExitSent) selectionExitSent=port.exitSelection(s)
                    } else if (!s.tagList && !s.rename && !s.detail &&
                        s.lines.none { GoWorkflowPolicy.normalized(it.text) == "done" } && (s.list || s.searchSuggestions)) {
                        busy=true
                        port.enter(query){ok,message->
                            busy=false
                            if(!closed){
                                if(!ok)finish(false,"prev解除後の再検索に失敗しました: "+message)
                                else{resetSearchWatchers();stage=7;lastObservationAt=null;pump.wake()}
                            }
                        }
                    }
                }
                7->{
                    if(s.list && s.count!=null){
                        if(s.count==0) {
                            if(emptyConfirmed(s))finish(true,"全${total}体の${tagName}解除を再検索で確認しました")
                        }else {
                            emptySince=null
                            val old=previous;previous=s
                            if(old!=null && GoWorkflowPolicy.sameResults(old,s,compareLabels=false))
                                finish(false,"${tagName}付きの対象が"+s.count+"件残っています。自動整理を停止しました")
                        }
                    }
                }
            }
            if(s==null){previous=null;emptySince=null}
            if(!closed)pump.schedule(lastObservationAt?.let{maxOf(0L,OBSERVATION_INTERVAL_MS-(clock.now()-it))} ?: 0L)
        }
    }
    private fun holdFirstResult(scene: GoWorkflowPolicy.Scene) {
        val cardBox = scene.cards.firstOrNull()?.box ?: port.fallbackFirstCard(scene) ?: return
        if (holdAttempts >= 3) {
            finish(false, "${tagName}解除の対象を選択できませんでした。検索結果を確認してください")
            return
        }
        holdAttempts++
        lastHoldAt = clock.now()
        if (port.hold(cardBox)) {
            heldFrom = scene
            stage = 2
        }
    }
    private fun emptyConfirmed(s:GoWorkflowPolicy.Scene):Boolean {
        if(!s.list || s.count!=0 || s.keyboard || s.cards.isNotEmpty() || s.visualCount>0){emptySince=null;return false}
        val since=emptySince ?: clock.now().also{emptySince=it}
        return clock.now()-since>=800L
    }
    private fun confirmDone(s:GoWorkflowPolicy.Scene){val button=s.tagDone?:return;if(port.tap(button))stage=6}
}

