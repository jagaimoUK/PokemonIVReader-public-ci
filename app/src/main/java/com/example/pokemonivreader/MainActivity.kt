package com.example.pokemonivreader

import android.content.Context
import com.example.pokemonivreader.catchassist.CatchTrialActivity
import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import java.io.File
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Phase 1Aから維持する共通データ形式。
data class Iv(val attack: Int, val defense: Int, val hp: Int)

private const val PREF_NAME = "pokemon_iv_reader"
private const val KEY_NAME = "pokemon_name"
private const val KEY_IVS = "iv_list"
private const val BATCH_MAX_IMAGES = 20
// Phase 2A: 保存したパネル範囲をアプリ再起動後も使う。
private const val KEY_CROP_TEMPLATE = "crop_template_v1"
private const val KEY_COLOR_SENSITIVITY = "bar_color_sensitivity_v1"
private const val CROP_NUDGE_STEP = 0.005f // ±0.5% of the entire screenshot

// 全画面画像に対する相対座標。端末の解像度に依存しない。
private data class PointRatio(val x: Float, val y: Float)

/** 画面比率が近い画像でだけ再利用する。画面比率が違えば手動指定が必要。 */
private data class CropTemplate(
    val first: PointRatio,
    val second: PointRatio,
    val imageAspectRatio: Float
) {
    fun matches(bitmap: Bitmap): Boolean {
        if (!imageAspectRatio.isFinite() || imageAspectRatio <= 0f) return false
        val now = bitmap.width.toFloat() / bitmap.height
        return abs(now - imageAspectRatio) / imageAspectRatio <= 0.025f
    }

    fun hasValidCorners(bitmap: Bitmap): Boolean =
        listOf(first.x, first.y, second.x, second.y).all { it.isFinite() && it in 0f..1f } &&
            abs(second.x - first.x) * bitmap.width >= 100f &&
            abs(second.y - first.y) * bitmap.height >= 100f
}

private data class Recognition(
    val iv: Iv,
    val confident: Boolean,
    val barsY: List<Float>,
    val warnings: List<String>
)
private data class CropAndRecognition(val cropped: Bitmap, val reading: Recognition?)

/** One result for each image, even on failure. Nothing is automatically saved. */
private data class BatchEntry(
    val index: Int,
    val guessed: Iv?,
    val confident: Boolean,
    val message: String?,
    val attackText: String,
    val defenseText: String,
    val hpText: String,
    val confirmed: Boolean = false,
    val added: Boolean = false,
    val barsY: List<Float> = emptyList(),
    val diagnostic: String? = null
)

private fun validIv(entry: BatchEntry): Iv? {
    val a = entry.attackText.toIntOrNull()
    val d = entry.defenseText.toIntOrNull()
    val h = entry.hpText.toIntOrNull()
    if (a == null || d == null || h == null || a !in 0..15 || d !in 0..15 || h !in 0..15) {
        return null
    }
    return Iv(a, d, h)
}


class MainActivity : ComponentActivity() {
    // Refresh the list after a float-button capture while Pokémon GO is in front.
    private val ivRevision = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { IvReaderScreen(ivRevision.intValue) } }
    }

    override fun onResume() {
        super.onResume()
        ivRevision.intValue += 1
    }
}

@Composable
internal fun IvReaderScreen(ivRevision: Int, refreshOnOpen: Boolean = true) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    var autoRecordRevision by remember { mutableIntStateOf(0) }
    var actionMessage by rememberSaveable { mutableStateOf("") }
    var showCalibration by remember { mutableStateOf(false) }
    var showReferences by remember { mutableStateOf(false) }
    var catalogUpdating by remember { mutableStateOf(false) }
    val catalogScope = rememberCoroutineScope()
    suspend fun refreshCatalog(force: Boolean) {
        catalogUpdating = true
        try {
            val status = withContext(Dispatchers.IO) { CatalogUpdates.refresh(context,force) }
            if (force) actionMessage = status
            autoRecordRevision++
        } finally { catalogUpdating = false }
    }
    LaunchedEffect(context) { if (refreshOnOpen) refreshCatalog(false) }
    val autoRecords = remember(ivRevision, autoRecordRevision) { AutoIvRecords.load(context) }
    var selectedRecords by remember { mutableStateOf(emptySet<String>()) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var historyShadowBusy by remember { mutableStateOf(false) }
    val historyShadowScope = rememberCoroutineScope()
    fun changeHistoryShadow(state: ShadowRecognizer.State) {
        if (historyShadowBusy) return
        val keys = if (selectedRecords.isEmpty()) autoRecords.map { it.selectionKey }.toSet() else selectedRecords.toSet()
        if (keys.isEmpty()) return
        historyShadowBusy = true
        historyShadowScope.launch {
            try {
                val changed = withContext(Dispatchers.IO) { AutoIvRecords.setShadowMany(context, keys, state) }
                actionMessage = if (changed >= 0) "${changed}件を${state.label}に変更しました" else "一括変更を保存できませんでした。履歴を読み直してください"
                autoRecordRevision++
            } finally { historyShadowBusy = false }
        }
    }
    val canUndoDelete = remember(ivRevision, autoRecordRevision) { AutoIvRecords.canUndo(context) }
    var targetMode by remember {
        mutableStateOf(RankingEligibility.Mode.fromStored(preferences.getString(RankingEligibility.MODE_KEY, null)))
    }
    // Resynchronize after a mode change made from the floating reader menu.
    LaunchedEffect(ivRevision) {
        targetMode = RankingEligibility.Mode.fromStored(preferences.getString(RankingEligibility.MODE_KEY, null))
    }
    var shadowMode by remember {
        mutableStateOf(ShadowRecognizer.Mode.fromStored(preferences.getString(ShadowRecognizer.MODE_KEY, null)))
    }
    LaunchedEffect(autoRecords) {
        selectedRecords = selectedRecords.intersect(autoRecords.map { it.selectionKey }.toSet())
    }

    // A single temporary screenshot calibrates the live reader; no batch OCR here.
    val imageUris = remember { mutableStateListOf<Uri>() }
    var selectionVersion by remember { mutableStateOf(0) }
    var calibrationBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var imageError by remember { mutableStateOf<String?>(null) }
    var loadingCalibration by remember { mutableStateOf(false) }
    val cropCorners = remember { mutableStateListOf<PointRatio>() }
    // 保存された範囲だけを再利用する。選択した画像自体は保存しない。
    var savedCropTemplate by remember(context) { mutableStateOf(loadCropTemplate(context)) }
    var cropMessage by remember { mutableStateOf<String?>(null) }
    var captureIssue by remember { mutableStateOf<String?>(null) }

    fun replaceImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        imageUris.clear()
        imageUris.addAll(uris.take(BATCH_MAX_IMAGES))
        cropCorners.clear()
        cropMessage = null
        calibrationBitmap = null
        imageError = null
        selectionVersion++
    }

    val calibrationPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked: Uri? ->
        showCalibration = picked != null
        if (picked != null) replaceImages(listOf(picked))
    }

    val projectionManager = remember(context) {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    fun projectionConsentIntent(): Intent = if (Build.VERSION.SDK_INT >= 34) {
        // Full-display mode, because the user will switch from our app to Pokémon GO.
        projectionManager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
    } else {
        projectionManager.createScreenCaptureIntent()
    }
    val captureConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            captureIssue = null
            val service = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            try {
                context.startForegroundService(service)
            } catch (e: Exception) {
                captureIssue = "開始できません: ${e.javaClass.simpleName}"
            }
        } else {
            captureIssue = "Androidの画面取得許可がキャンセルされました。"
        }
    }
    fun startOrShowCapture() {
        if(ScreenCaptureService.isSessionActive) {
            try {
                context.startService(Intent(context,ScreenCaptureService::class.java).apply {
                    action=ScreenCaptureService.ACTION_SHOW_OVERLAY
                })
                captureIssue=null
            } catch(e:Exception) { captureIssue="再表示できません: ${e.javaClass.simpleName}" }
        } else captureConsent.launch(projectionConsentIntent())
    }
    val notificationsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startOrShowCapture()
        } else {
            // Notification permission may be denied; overlay still works on supported Android versions.
            captureIssue = "通知は未許可です。フロート操作は引き続き試せますが、通知が表示されない場合があります。"
            startOrShowCapture()
        }
    }

    // Overlay permission is separate from screen-recording consent.
    val overlayPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startOrShowCapture()
            }
        } else {
            captureIssue = "フロート表示の許可が必要です。設定で『他のアプリの上に表示』を有効にしてね。"
        }
    }

    LaunchedEffect(selectionVersion) {
        calibrationBitmap = null
        imageError = null
        val uri = imageUris.firstOrNull() ?: return@LaunchedEffect
        loadingCalibration = true
        try {
            val loaded = withContext(Dispatchers.IO) {
                decodeForAnalysis(context, uri)
            }
            calibrationBitmap = loaded
            // 前回と画面比率・パネル範囲が矛盾しない場合にだけ自動適用。
            val template = savedCropTemplate
            if (template != null) {
                if (template.matches(loaded) && template.hasValidCorners(loaded)) {
                    cropCorners.clear()
                    cropCorners.add(template.first)
                    cropCorners.add(template.second)
                    cropMessage = "保存済みの範囲を表示しています"
                } else {
                    cropMessage = "パネルの左上と右下を指定してください"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            imageError = "最初の画像を読み込めません。画像を選び直してください。"
        } finally {
            loadingCalibration = false
        }
    }

    fun deleteRecords(keys:Set<String>) {
        val deleted=AutoIvRecords.deleteMany(context,keys)
        actionMessage=if(deleted>=0) "" else "削除できませんでした"
        if(deleted>=0) selectedRecords=selectedRecords-keys
        autoRecordRevision++
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("自動履歴を全削除") },
            text = { Text("${autoRecords.size}件の自動読み取り履歴を削除します。画像の見本と比較画像は「見本を管理」から削除できます。厳選コピーリストの保存履歴は消去しません。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    deleteRecords(autoRecords.map { it.selectionKey }.toSet())
                }) { Text("すべて削除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text("キャンセル") }
            }
        )
    }

    if (showCalibration) {
        AlertDialog(
            onDismissRequest = { showCalibration = false }, title = { Text("読み取り範囲") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loadingCalibration) CircularProgressIndicator()
                imageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                calibrationBitmap?.let { bitmap ->
                    Text(if (cropCorners.size < 2) "個体値パネルの左上と右下をタップ" else "範囲を保存しました")
                    ImageWithCropCorners(bitmap, cropCorners, height = 300.dp) { point ->
                        if (cropCorners.size < 2) {
                            cropCorners.add(point)
                            if (cropCorners.size == 2) {
                                val template = CropTemplate(cropCorners[0], cropCorners[1], bitmap.width.toFloat() / bitmap.height)
                                if (template.hasValidCorners(bitmap)) { saveCropTemplate(context, template); savedCropTemplate = template; cropMessage = null }
                                else { cropCorners.clear(); cropMessage = "範囲を広めに指定してください" }
                            }
                        }
                    }
                    cropMessage?.let { Text(it) }
                }
            } },
            confirmButton = { TextButton(onClick = { showCalibration = false }) { Text("閉じる") } },
            dismissButton = { TextButton(onClick = { cropCorners.clear(); cropMessage = null }) { Text("やり直す") } }
        )
    }
    if (showReferences) {
        AlertDialog(
            onDismissRequest = { showReferences = false }, title = { Text("見本・バックアップ") },
            text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                VisualMemoryPanel(autoRecordRevision) { message -> actionMessage = message; autoRecordRevision++ }
                VisualCloudControls { message -> actionMessage = message; autoRecordRevision++ }
                TextButton(onClick = { catalogScope.launch { refreshCatalog(true) } }, enabled = !catalogUpdating) { Text("図鑑を更新") }
                if (catalogUpdating) CircularProgressIndicator()
            } },
            confirmButton = { TextButton(onClick = { showReferences = false }) { Text("閉じる") } }
        )
    }

    val listState = rememberLazyListState()
    val navigationScope = rememberCoroutineScope()
    val homeScroll = rememberScrollState()
    // Keep the primary start action at 70% of the usable viewport on opening.
    val copyIndex = 0
    LaunchedEffect(ivRevision) { listState.scrollToItem(copyIndex); homeScroll.scrollTo(0) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().imePadding().testTag("reader-viewport")) {
    val homeHeight = (maxHeight * 0.7f + 12.dp).coerceAtLeast(280.dp)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("reader-home"),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 86.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "home-actions") {
            Column(modifier = Modifier.fillMaxWidth().height(homeHeight)) {
                Column(modifier = Modifier.weight(1f).verticalScroll(homeScroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("個体値読み取り", style = MaterialTheme.typography.headlineSmall)
                    Text("GOの評価画面から個体値・CPを読み取り、履歴に保存します。開始後はGOを開き、読み取りフロートを操作してください。",
                        style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = {
                    val text = AutoIvRecords.prompt(autoRecords)
                    ClipboardCopy.write(context, "個体値厳選", text)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = autoRecords.any { it.targets.isNotEmpty() }
            ) { Text("自動登録データをコピー（進化先別）") }
            Button(onClick = { ReaderWebTransfer.send(context) }, modifier = Modifier.fillMaxWidth(),
                enabled = autoRecords.any { it.targets.isNotEmpty() }) { Text("厳選アプリへ送る") }
                    Text("送る：厳選アプリで取り込み・比較へ。コピー：手動貼り付け用。",
                        style = MaterialTheme.typography.bodySmall)
                }
            Button(
                onClick = {
                    captureIssue = null
                    if(ScreenCaptureService.isSessionActive && Settings.canDrawOverlays(context)) {
                        startOrShowCapture()
                    } else if (!Settings.canDrawOverlays(context)) {
                        overlayPermission.launch(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"))
                        )
                    } else if (Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        startOrShowCapture()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("reader-start")
            ) { Text("読み取りを開始") }
            }
        }
        item {
            Text("読み取りを隠す：表示だけを閉じます。画面共有を終了：読み取りを停止します。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick={
                if(ScreenCaptureService.isSessionActive) context.startService(Intent(context,ScreenCaptureService::class.java).apply {
                    action=ScreenCaptureService.ACTION_HIDE_OVERLAY
                })
            }) { Text("読み取りを隠す") }
            captureIssue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Button(
                onClick = {
                    context.startService(Intent(context, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_STOP
                    })
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("画面共有を終了") }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { calibrationPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("読み取り範囲") }
                TextButton(onClick = { showReferences = true }) { Text("見本・バックアップ") }
            }
        }
        item { Text("読み取り範囲：評価パネルの位置を調整。見本・バックアップ：姿の見本と保存データを管理。", style = MaterialTheme.typography.bodySmall) }
        item {
            Button(onClick = {
                context.startActivity(Intent(context, ReaderBackupActivity::class.java))
            }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Text("Androidの履歴・検索キューをバックアップ")
            }
        }
        item {
            TextButton(onClick = {
                context.startActivity(Intent(context, CatchTrialActivity::class.java))
            }) { Text("捕獲の試験版") }
        }
        item {
            TextButton(onClick = {
                context.startActivity(Intent(context, ReaderIndividualSyncActivity::class.java))
            }) { Text("確認済み個体の同期") }
        }
        if(actionMessage.isNotBlank()) { item { Text(actionMessage,style=MaterialTheme.typography.bodySmall) } }
        item {
            Text("自動読み取り履歴", style = MaterialTheme.typography.titleMedium)
            Row {
                RankingEligibility.Mode.entries.forEach { mode ->
                    TextButton(onClick = {
                        val saved = preferences.edit().putString(RankingEligibility.MODE_KEY, mode.name).commit()
                        if (saved) {
                            targetMode = mode
                            autoRecordRevision++
                        } else actionMessage = "対象モードを保存できませんでした"
                    }, enabled = targetMode != mode) { Text(if (mode == RankingEligibility.Mode.ALL_SPECIES) "全体" else "厳選") }
                }
            }
            Row {
                ShadowRecognizer.Mode.entries.forEach { mode ->
                    TextButton(onClick = {
                        shadowMode = mode
                        preferences.edit().putString(ShadowRecognizer.MODE_KEY, mode.name).apply()
                    }, enabled = shadowMode != mode) { Text(mode.label) }
                }
            }
            Row {
                TextButton(onClick = { selectedRecords = autoRecords.map { it.selectionKey }.toSet() }, enabled = autoRecords.isNotEmpty()) { Text("全選択") }
                TextButton(onClick = { selectedRecords = emptySet() }, enabled = selectedRecords.isNotEmpty()) { Text("選択解除") }
                TextButton(onClick = { deleteRecords(selectedRecords.toSet()) }, enabled = selectedRecords.isNotEmpty()) { Text("選択${selectedRecords.size}件を削除") }
            }

            val shadowScopeCount = if (selectedRecords.isEmpty()) autoRecords.size else selectedRecords.size
            val shadowScopeLabel = if (selectedRecords.isEmpty()) "全${shadowScopeCount}件" else "選択${shadowScopeCount}件"
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { changeHistoryShadow(ShadowRecognizer.State.NORMAL) }, enabled = shadowScopeCount > 0 && !historyShadowBusy,
                    modifier = Modifier.weight(1f)) { Text("${shadowScopeLabel}を通常に") }
                Button(onClick = { changeHistoryShadow(ShadowRecognizer.State.SHADOW) }, enabled = shadowScopeCount > 0 && !historyShadowBusy,
                    modifier = Modifier.weight(1f)) { Text("${shadowScopeLabel}をシャドウに") }
            }
            if (historyShadowBusy) Text("まとめて変更しています…", style = MaterialTheme.typography.bodySmall)
        }
        if (autoRecords.isEmpty()) {
            item { Text("自動登録はまだありません。", style = MaterialTheme.typography.bodySmall) }
        } else {
            itemsIndexed(autoRecords, key = { _, row -> row.selectionKey }) { index, row ->
                Column {
                    Row(horizontalArrangement = Arrangement.SpaceBetween,modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = row.selectionKey in selectedRecords, onCheckedChange = { selected ->
                            selectedRecords = if (selected) selectedRecords + row.selectionKey else selectedRecords - row.selectionKey
                        })
                        val nameLabel = if (row.source == "要確認" || row.source.isBlank()) "名前未確認" else row.source
                        val cpLabel = if (row.cp > 0) "CP${row.cp}" else "CP未認識"
                        Text("${index+1}. $nameLabel  $cpLabel  ${row.iv}", modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            deleteRecords(setOf(row.selectionKey))
                        }) { Text("削除") }
                    }
                    Text("区分: ${row.shadow.label}" + if (row.shadow == ShadowRecognizer.State.UNKNOWN && row.shadowHint == "SHADOW")
                        "（紫のオーラからシャドウ候補）" else "", style = MaterialTheme.typography.bodySmall)
                    Row {
                        listOf(ShadowRecognizer.State.NORMAL, ShadowRecognizer.State.SHADOW, ShadowRecognizer.State.UNKNOWN).forEach { choice ->
                            TextButton(onClick = {
                                if (!AutoIvRecords.setShadow(context, index, row, choice)) {
                                    actionMessage = "保存できませんでした。履歴を読み直してください"
                                }
                                autoRecordRevision++
                            }, enabled = row.shadow != choice && !historyShadowBusy) {
                                Text(when (choice) {
                                    ShadowRecognizer.State.NORMAL -> "通常に変更"
                                    ShadowRecognizer.State.SHADOW -> "シャドウに変更"
                                    ShadowRecognizer.State.UNKNOWN -> "保留"
                                })
                            }
                        }
                    }
                    if (row.shadowDecision.isNotBlank()) Text(row.shadowDecision, style = MaterialTheme.typography.bodySmall)
                    if (row.genderRelevant) {
                        Text("性別: ${row.gender.label}", style = MaterialTheme.typography.bodySmall)
                        Row {
                            listOf(GenderRecognizer.Gender.MALE, GenderRecognizer.Gender.FEMALE, GenderRecognizer.Gender.UNKNOWN).forEach { choice ->
                                TextButton(onClick = {
                                    if (!AutoIvRecords.setGender(context, row, choice)) actionMessage = "性別を保存できませんでした"
                                    autoRecordRevision++
                                }, enabled = row.gender != choice) { Text(if(choice == GenderRecognizer.Gender.UNKNOWN) "性別保留" else choice.label) }
                            }
                        }
                    }
                    if (row.genderPending.isNotEmpty()) Text("性別条件の確認待ち: ${row.genderPending.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
                    Text("${row.eligibilityLabel} · 厳選対象: ${row.targets.joinToString("、").ifBlank { "なし" }}",style = MaterialTheme.typography.bodySmall)
                    if (row.uncertain.isNotEmpty()) Text("CP判定保留: ${row.uncertain.joinToString("、")}",style = MaterialTheme.typography.bodySmall)
                    val pumpkinFamily = PumpkinForms.familyFromId(row.sourceId)
                        ?: PumpkinForms.familyFromText(row.nameOcr)
                    if (pumpkinFamily != null) {
                        val sizeNote = when (row.sizeDecision) {
                            "height" -> "Height OCR：${row.heightBadge}"
                            "manual" -> "手動で確定"
                            "weight" -> "旧Weight判定：再確認が必要"
                            else -> "Height未確認"
                        }
                        Text("サイズ：$sizeNote", style = MaterialTheme.typography.bodySmall,
                            color = if (row.sizeDecision == "weight") MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                        // Four compact chips on ONE row instead of two rows of large buttons.
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            PumpkinForms.sizes.forEach { form ->
                                val selected = row.sourceId == "${pumpkinFamily}_$form" && row.sizeDecision in setOf("height", "manual")
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        actionMessage = AutoIvRecords.selectPumpkinSize(context, row, form)
                                        autoRecordRevision++
                                    },
                                    label = { Text(PumpkinForms.sizeLabel(form), style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    VisualFormControls(row,autoRecordRevision) { message -> actionMessage=message; autoRecordRevision++ }
                    if (row.cp <= 0) {
                        val holdLabel = when (row.readerHoldState) {
                            "CP_MISSING", "TAGGING" -> "CP不明・保留処理中"
                            "TAGGED" -> "CP不明・GOの保留タグを確認済み"
                            "TAG_FAILED" -> "CP不明・GOの保留タグを確認できませんでした"
                            else -> null
                        }
                        if (holdLabel != null) Text(holdLabel, style = MaterialTheme.typography.bodySmall)
                    }
                    if (row.diagnostic.isNotBlank()) Text("確認: ${row.diagnostic}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (row.nameOcr.isNotBlank() && (row.source == "要確認" || row.shadow == ShadowRecognizer.State.UNKNOWN)) {
                        Text("文字認識の結果: ${row.nameOcr.replace("\n", " / ").take(100)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            TextButton(
                onClick = { confirmDeleteAll = true },
                enabled = autoRecords.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("自動履歴を全削除") }
            TextButton(
                onClick = {
                    val restored = AutoIvRecords.undoDelete(context)
                    actionMessage = if (restored >= 0) "${restored}件を復元しました" else "復元できませんでした"
                    autoRecordRevision++
                },
                enabled = canUndoDelete,
                modifier = Modifier.fillMaxWidth()
            ) { Text("直前の削除を取り消す") }
        }

    }
    Column(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { context.startActivity(Intent(context, FlashcardActivity::class.java)) },
            modifier = Modifier.width(72.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF516C5D))) { Text("技カード") }
        Button(onClick = { navigationScope.launch { listState.scrollToItem(copyIndex); homeScroll.scrollTo(0) } },
            modifier = Modifier.width(72.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF516C5D))) { Text("コピーへ") }

    }
    }
}

/** 画像全体を縮小表示し、ユーザーの2回のタップを元画像の比率座標に変換する。 */
@Composable
private fun ImageWithCropCorners(
    bitmap: Bitmap,
    corners: List<PointRatio>,
    height: Dp = 460.dp,
    onCorner: (PointRatio) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(Color(0xFF252525), RoundedCornerShape(8.dp))
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "スクリーンショット。個体値パネルの左上と右下をタップ",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap, corners.size) {
                    detectTapGestures { tap ->
                        val viewWidth = size.width.toFloat()
                        val viewHeight = size.height.toFloat()
                        val scale = min(viewWidth / bitmap.width, viewHeight / bitmap.height)
                        val imageWidth = bitmap.width * scale
                        val imageHeight = bitmap.height * scale
                        val startX = (viewWidth - imageWidth) / 2f
                        val startY = (viewHeight - imageHeight) / 2f
                        if (tap.x in startX..(startX + imageWidth) &&
                            tap.y in startY..(startY + imageHeight)
                        ) {
                            onCorner(
                                PointRatio(
                                    ((tap.x - startX) / imageWidth).coerceIn(0f, 1f),
                                    ((tap.y - startY) / imageHeight).coerceIn(0f, 1f)
                                )
                            )
                        }
                    }
                }
        ) {
            val scale = min(size.width / bitmap.width, size.height / bitmap.height)
            val imageWidth = bitmap.width * scale
            val imageHeight = bitmap.height * scale
            val startX = (size.width - imageWidth) / 2f
            val startY = (size.height - imageHeight) / 2f
            val positions = corners.map {
                Offset(startX + it.x * imageWidth, startY + it.y * imageHeight)
            }
            positions.forEach { p ->
                drawCircle(Color.Cyan, radius = 9.dp.toPx(), center = p)
                drawCircle(Color.Black, radius = 9.dp.toPx(), center = p, style = Stroke(2.dp.toPx()))
            }
            if (positions.size == 2) {
                val a = positions[0]
                val b = positions[1]
                drawRect(
                    color = Color.Cyan,
                    topLeft = Offset(min(a.x, b.x), min(a.y, b.y)),
                    size = Size(abs(a.x - b.x), abs(a.y - b.y)),
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }
    }
}

/** Phase 2C: visual guide for which lines the algorithm judged to be the three bars. */
@Composable
private fun ImageWithDetectedBars(bitmap: Bitmap, barY: List<Float>) {
    Box(modifier = Modifier.fillMaxWidth().height(210.dp)) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "個体値パネルの認識位置確認",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scale = min(size.width / bitmap.width, size.height / bitmap.height)
            val imageW = bitmap.width * scale
            val imageH = bitmap.height * scale
            val x0 = (size.width - imageW) / 2f
            val y0 = (size.height - imageH) / 2f
            barY.forEach { fraction ->
                val y = y0 + imageH * fraction.coerceIn(0f, 1f)
                drawLine(
                    color = Color(0xFF29E35C),
                    start = Offset(x0, y),
                    end = Offset(x0 + imageW, y),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
    }
}

/** Read only our app-private, previously captured PNG files, oldest first. */
private fun capturedImages(context: Context): List<File> =
    File(context.filesDir, ScreenCaptureService.CAPTURE_DIR)
        .listFiles { f -> f.isFile && f.name.startsWith("capture_") && f.name.endsWith(".png") }
        ?.sortedBy { it.name }
        ?.takeLast(BATCH_MAX_IMAGES)
        ?: emptyList()

/** 高解像度スクリーンショットでもメモリ使用量を抑える。ピクセル参照可能なSOFTWAREにする。 */
private fun decodeForAnalysis(context: Context, uri: Uri): Bitmap {
    val source = if (uri.scheme == "file") {
        val path = uri.path ?: throw IllegalArgumentException("No image path")
        val file = File(path)
        // Direct capture images are only read from this app's private directory.
        val root = File(context.filesDir, ScreenCaptureService.CAPTURE_DIR).canonicalFile
        require(file.canonicalFile.parentFile == root) { "Invalid local capture path" }
        ImageDecoder.createSource(file)
    } else {
        ImageDecoder.createSource(context.contentResolver, uri)
    }
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longer = max(info.size.width, info.size.height)
        var sample = 1
        while (longer / sample > 2600) sample *= 2
        if (sample > 1) decoder.setTargetSampleSize(sample)
    }
}

/** Crop with a common normalized rectangle; used by both analysis and review. */
private fun cropOnly(bitmap: Bitmap, p1: PointRatio, p2: PointRatio): Bitmap {
    val left = (min(p1.x, p2.x) * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
    val right = (max(p1.x, p2.x) * bitmap.width).roundToInt().coerceIn(left + 1, bitmap.width)
    val top = (min(p1.y, p2.y) * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
    val bottom = (max(p1.y, p2.y) * bitmap.height).roundToInt().coerceIn(top + 1, bitmap.height)
    val roi = Rect(left, top, right, bottom)
    require(roi.width() >= 100 && roi.height() >= 100) { "範囲が小さすぎます" }
    return Bitmap.createBitmap(bitmap, roi.left, roi.top, roi.width(), roi.height())
}

/** Crop panel and find three bar fill levels. */
private fun cropAndRead(
    bitmap: Bitmap, p1: PointRatio, p2: PointRatio,
    sensitivity: IvBarRecognizer.Sensitivity
): CropAndRecognition {
    val cropped = cropOnly(bitmap, p1, p2)
    return CropAndRecognition(cropped, findIvBars(cropped, sensitivity))
}

/**
 * Phase 2C: 色感度と診断情報を独立した認識エンジンから受け取る。
 * UI・保存・画面取得を変更せず、認識アルゴリズムだけ交換できる。
 */
private fun findIvBars(bitmap: Bitmap, sensitivity: IvBarRecognizer.Sensitivity): Recognition? {
    val w = bitmap.width
    val h = bitmap.height
    if (w < 100 || h < 100) return null
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val result = IvBarRecognizer.read(pixels, w, h, sensitivity) ?: return null
    return Recognition(
        Iv(result.attack, result.defense, result.hp),
        result.reliable,
        result.barCenters,
        result.warnings
    )
}

@Composable
private fun IvNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            if (next.length <= 2 && next.all { it in '0'..'9' }) onValueChange(next)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

private fun buildPrompt(name: String, ivs: List<Iv>): String = buildString {
    appendLine("個体値厳選")
    appendLine()
    appendLine(IvSelectionText.targetName(name))
    ivs.forEach { iv -> appendLine("${iv.attack},${iv.defense},${iv.hp}") }
}.trimEnd()

private fun saveIvs(context: Context, ivs: List<Iv>) {
    val json = JSONArray()
    ivs.forEach { iv ->
        json.put(JSONObject().apply {
            put("attack", iv.attack)
            put("defense", iv.defense)
            put("hp", iv.hp)
        })
    }
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit().putString(KEY_IVS, json.toString()).apply()
}

private fun loadIvs(context: Context): List<Iv> {
    val saved = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .getString(KEY_IVS, "[]") ?: "[]"
    return try {
        val json = JSONArray(saved)
        buildList {
            for (i in 0 until json.length()) {
                val item = json.getJSONObject(i)
                val iv = Iv(item.getInt("attack"), item.getInt("defense"), item.getInt("hp"))
                if (iv.attack in 0..15 && iv.defense in 0..15 && iv.hp in 0..15) add(iv)
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}


/** Phase 2A: パネルの2点だけ保存。画像本体はここには保存しない。 */
private fun saveCropTemplate(context: Context, template: CropTemplate) {
    val json = JSONObject().apply {
        put("x1", template.first.x.toDouble())
        put("y1", template.first.y.toDouble())
        put("x2", template.second.x.toDouble())
        put("y2", template.second.y.toDouble())
        put("aspect", template.imageAspectRatio.toDouble())
    }
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit().putString(KEY_CROP_TEMPLATE, json.toString()).apply()
}

private fun loadCropTemplate(context: Context): CropTemplate? {
    val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .getString(KEY_CROP_TEMPLATE, null) ?: return null
    return try {
        val json = JSONObject(raw)
        val crop = CropTemplate(
            PointRatio(json.getDouble("x1").toFloat(), json.getDouble("y1").toFloat()),
            PointRatio(json.getDouble("x2").toFloat(), json.getDouble("y2").toFloat()),
            json.getDouble("aspect").toFloat()
        )
        if (crop.imageAspectRatio.isFinite() && crop.imageAspectRatio > 0f &&
            listOf(crop.first.x, crop.first.y, crop.second.x, crop.second.y)
                .all { it.isFinite() && it in 0f..1f }
        ) crop else null
    } catch (_: Exception) {
        null
    }
}

private fun clearCropTemplate(context: Context) {
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit().remove(KEY_CROP_TEMPLATE).apply()
}


