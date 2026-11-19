package com.example.pokemonivreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VisualFormControls(row:AutoIvRecords.Row, revision:Int, onChanged:(String)->Unit) {
    val context=LocalContext.current
    val candidates=remember(row.nameCandidate,revision) { PokemonCatalog.get(context).visualCandidates(row.nameCandidate) }
    var query by remember(row.recordId) { mutableStateOf("") }
    val choices=remember(query,candidates) { if(query.isBlank())candidates else PokemonCatalog.get(context).searchSpecies(query) }
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var hasImage by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(row.recordId,revision) {
        hasImage=withContext(Dispatchers.IO) { VisualFormMemory.store(context).feature(row.recordId)!=null }
    }
    LaunchedEffect(open,row.recordId,revision) {
        preview=if(open) withContext(Dispatchers.IO) {
            VisualFormMemory.store(context).thumbnail(row.recordId)?.let { BitmapFactory.decodeFile(it.absolutePath) }
        } else null
    }
    if(row.manualFormId.isNotBlank()) Text("姿：手動で確定",style=MaterialTheme.typography.bodySmall)
    if(row.visualNote.isNotBlank()) Text(row.visualNote,style=MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(onClick={open=true},enabled=!busy) { Text("名前・姿を訂正して見本に追加") }
        if(hasImage && row.manualFormId.isBlank()) TextButton(onClick={
            busy=true
            scope.launch {
                try { onChanged(withContext(Dispatchers.IO) { AutoIvRecords.retryVisual(context,row) }) }
                finally { busy=false }
            }
        },enabled=!busy) { Text("見本で再判定") }
    }
    if(open) AlertDialog(
        onDismissRequest={if(!busy)open=false},
        title={Text("画像の正しい名前・姿を教える")},
        text={Column {
            preview?.let { Image(it.asImageBitmap(),"この履歴で読み取った画像",modifier=Modifier.size(160.dp)) }
            Text(if(hasImage) "選んだ姿で履歴を訂正し、この画像を見本にします。" else
                "この履歴には比較画像がありません。姿を訂正できますが、見本の追加には再読み取りが必要です。")
            OutlinedTextField(value=query,onValueChange={query=it.take(80)},label={Text("正しいポケモン名（日本語・英語）")},singleLine=true,modifier=Modifier.fillMaxWidth())
            if(choices.isEmpty())Text("正しい名前を入力し、種・性別・姿を選んでください。OCRが誤読していても登録できます。")
            LazyColumn(modifier=Modifier.heightIn(max=300.dp)) {
                items(choices,key={it.id}) { species ->
                    TextButton(onClick={
                        busy=true
                        scope.launch {
                            try {
                                onChanged(withContext(Dispatchers.IO) { AutoIvRecords.selectForm(context,row,species.id) })
                                open=false
                            } finally { busy=false }
                        }
                    },enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text(species.displayName) }
                }
            }
        }},
        confirmButton={},dismissButton={TextButton(onClick={open=false},enabled=!busy){Text("閉じる")}}
    )
}

@Composable
internal fun VisualMemoryPanel(revision:Int,onChanged:(String)->Unit) {
    val context=LocalContext.current
    var open by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var samples by remember { mutableStateOf(emptyList<VisualMemoryIndex.Sample>()) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(revision,open) {
        samples=withContext(Dispatchers.IO) { VisualFormMemory.store(context).read().samples }
    }
    Text("画像の見本：${samples.size}件",style=MaterialTheme.typography.bodySmall)
    Text("タイプで確定できた画像と、手動で選んだ正解をスマホ内に保存します。画像比較の予測だけでは見本を増やしません。",
        style=MaterialTheme.typography.bodySmall)
    TextButton(onClick={open=true}) { Text("見本を管理") }
    if(open) AlertDialog(
        onDismissRequest={if(!busy)open=false},title={Text("保存した見本（${samples.size}件）")},
        text={Column {
            Text("見本の削除は今後の比較に反映します。確定済みの履歴は、履歴側の「姿を確認」で訂正できます。")
            LazyColumn(modifier=Modifier.heightIn(max=380.dp)) {
                items(samples.reversed(),key={it.recordId}) { sample ->
                    SampleItem(sample) {
                        busy=true
                        scope.launch {
                            try {
                                val ok=withContext(Dispatchers.IO) { VisualFormMemory.store(context).forget(sample.recordId) }
                                if(ok) samples=samples.filterNot { it.recordId==sample.recordId }
                                onChanged(if(ok) "見本を削除しました" else "見本を削除できませんでした")
                            } finally { busy=false }
                        }
                    }
                }
            }
            if(samples.isEmpty()) Text("まだ見本がありません。履歴の「名前・姿を訂正して見本に追加」から登録できます。")
        }},
        confirmButton={TextButton(onClick={confirmClear=true},enabled=!busy){Text("見本と比較画像を全削除")}},
        dismissButton={TextButton(onClick={open=false},enabled=!busy){Text("閉じる")}}
    )
    if(confirmClear) AlertDialog(
        onDismissRequest={confirmClear=false},title={Text("画像の見本を全削除")},
        text={Text("保存した見本と比較用画像を削除します。読み取り履歴は残ります。")},
        confirmButton={TextButton(onClick={
            busy=true
            scope.launch {
                try {
                    val ok=withContext(Dispatchers.IO) { VisualFormMemory.store(context).clear() }
                    if(ok)samples=emptyList()
                    confirmClear=false
                    onChanged(if(ok) "見本と比較画像を削除しました" else "削除できませんでした")
                } finally { busy=false }
            }
        },enabled=!busy){Text("削除する")}},dismissButton={TextButton(onClick={confirmClear=false},enabled=!busy){Text("キャンセル")}}
    )
}

@Composable
private fun SampleItem(sample:VisualMemoryIndex.Sample,onDelete:()->Unit) {
    val context=LocalContext.current
    var preview by remember(sample.recordId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(sample.recordId) {
        preview=withContext(Dispatchers.IO) { VisualFormMemory.store(context).thumbnail(sample.recordId)?.let { BitmapFactory.decodeFile(it.absolutePath) } }
    }
    Row {
        preview?.let { Image(it.asImageBitmap(),null,modifier=Modifier.size(56.dp)) }
        Column(modifier=Modifier.weight(1f)) {
            Text(PokemonCatalog.get(context).findById(sample.speciesId)?.displayName ?: sample.speciesId)
            Text(if(sample.origin=="manual") "手動で確認" else "タイプ表示から登録",style=MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick=onDelete){Text("削除")}
    }
}
